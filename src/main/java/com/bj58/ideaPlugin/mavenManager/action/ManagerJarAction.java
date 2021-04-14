package com.bj58.ideaPlugin.mavenManager.action;

import com.intellij.ide.IdeView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.xml.DomFileElement;
import com.intellij.util.xml.DomManager;
import org.jetbrains.idea.maven.dom.model.MavenDomDependencies;
import org.jetbrains.idea.maven.dom.model.MavenDomDependency;
import org.jetbrains.idea.maven.dom.model.MavenDomDependencyManagement;
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel;
import org.jetbrains.idea.maven.model.MavenArtifact;
import org.jetbrains.idea.maven.model.MavenArtifactNode;
import org.jetbrains.idea.maven.model.MavenConstants;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.idea.maven.project.MavenProjectsManager;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Add jar of conflict into dependencyManagement.
 *
 */
public class ManagerJarAction extends AnAction {

    /**
     * Execute manager of jar.
     */
    @Override
    public void actionPerformed(AnActionEvent e) {
        IdeView ideView = e.getData(LangDataKeys.IDE_VIEW);
        PsiDirectory chooseDirectory = ideView.getOrChooseDirectory();
        PsiFile psiFile = chooseDirectory.findFile("pom.xml");
        VirtualFile virtualFile = psiFile.getVirtualFile();
        MavenProject mavenProject = MavenProjectsManager.getInstance(e.getProject()).findProject(virtualFile);
        if (mavenProject == null) {
            return;
        }

        if (!(psiFile instanceof XmlFile)) {
            return;
        }
        XmlFile xmlFile = (XmlFile) psiFile;
        DomFileElement<MavenDomProjectModel> fileElement = DomManager.getDomManager(e.getProject())
                .getFileElement(xmlFile, MavenDomProjectModel.class);
        MavenDomProjectModel rootElement = fileElement.getRootElement();
        MavenDomDependencyManagement dependencyManagement = rootElement.getDependencyManagement();
        MavenDomDependencies domDependencies = dependencyManagement.getDependencies();

        MavenDomDependencies dependencies = rootElement.getDependencies();
        List<MavenDomDependency> domDependencyList = dependencies.getDependencies();

        CommandProcessor.getInstance().executeCommand(e.getProject(),
                () -> ApplicationManager.getApplication().runWriteAction(() -> {
                    List<MavenArtifact> conflictArtifactList = getConflictArtifacts(mavenProject);
                    // Add dependencyManager.
                    conflictArtifactList.forEach(mavenArtifact -> {
                        MavenDomDependency dependency = domDependencies.addDependency();
                        dependency.getGroupId().setValue(mavenArtifact.getGroupId());
                        dependency.getArtifactId().setValue(mavenArtifact.getArtifactId());
                        dependency.getVersion().setValue(mavenArtifact.getVersion());
                    });
                    // Delete version of conflict jar in dependencies.
                    domDependencyList.forEach(mavenDomDependency -> {
                        String groupId = mavenDomDependency.getGroupId().getStringValue();
                        String artifactId = mavenDomDependency.getArtifactId().getStringValue();
                        conflictArtifactList.forEach(conflictArtifact -> {
                            if (groupId.equals(conflictArtifact.getGroupId())
                                    && artifactId.equals(conflictArtifact.getArtifactId())) {
                                mavenDomDependency.getVersion().setValue(null);
                            }
                        });
                    });
                }), "MavenManager", "com.bj58.ideaPlugin");
    }

    /**
     * Show this button on pom.xml.
     */
    @Override
    public void update(AnActionEvent e) {
        Presentation presentation = e.getPresentation();

        IdeView ideView = e.getData(LangDataKeys.IDE_VIEW);
        if (ideView == null) {
            return;
        }

        PsiDirectory chooseDirectory = ideView.getOrChooseDirectory();
        if (chooseDirectory == null) {
            return;
        }

        PsiFile psiFile = chooseDirectory.findFile("pom.xml");
        if (psiFile == null) {
            presentation.setVisible(false);
            return;
        }
        VirtualFile virtualFile = psiFile.getVirtualFile();
        String path = virtualFile.getPath();

        if (!path.endsWith("/" + MavenConstants.POM_XML)) {
            presentation.setVisible(false);
            return;
        }

        MavenProjectsManager mavenProjectsManager = MavenProjectsManager.getInstance(e.getProject());
        if (mavenProjectsManager == null) {
            presentation.setVisible(false);
            return;
        }
        MavenProject managerProject = mavenProjectsManager.findProject(virtualFile);
        if (managerProject == null) {
            presentation.setVisible(false);
            return;
        }
        boolean visible = managerProject.getPath().equals(path);
        presentation.setVisible(visible);
    }

    private List<MavenArtifact> getConflictArtifacts(MavenProject mavenProject) {
        List<MavenArtifactNode> dependencyTree = mavenProject.getDependencyTree();
        Map<String, List<MavenArtifactNode>> allArtifactsMap = createAllArtifactsMap(dependencyTree);
        List<MavenArtifact> mavenArtifactList = new LinkedList<>();
        allArtifactsMap.forEach((artifactKey, artifactNodeList) -> {
            if (this.hasConflicts(artifactNodeList)) {
                MavenArtifactNode mavenArtifactNode = artifactNodeList.get(0);
                MavenArtifact mavenArtifact = mavenArtifactNode.getArtifact();
                mavenArtifactList.add(new MavenArtifact(mavenArtifact.getGroupId(),
                        mavenArtifact.getArtifactId(), this.getMaxVersion(artifactNodeList),
                        mavenArtifact.getBaseVersion(), mavenArtifact.getType(), mavenArtifact.getClassifier(),
                        mavenArtifact.getScope(), mavenArtifact.isOptional(), mavenArtifact.getExtension(),
                        mavenArtifact.getFile(), mavenProject.getLocalRepository(), mavenArtifact.isResolved(),
                        false));
            }
        });
        return mavenArtifactList;
    }

    private Map<String, List<MavenArtifactNode>> createAllArtifactsMap(List<MavenArtifactNode> dependencyTree) {
        Map<String, List<MavenArtifactNode>> allArtifactsMap = new TreeMap<>();
        this.addArtifacts(allArtifactsMap, dependencyTree, 0);
        return allArtifactsMap;
    }

    private void addArtifacts(Map<String, List<MavenArtifactNode>> artifactsMap,
                              List<MavenArtifactNode> artifactNodeList, int i) {
        if (artifactsMap == null || i > 100) {
            return;
        }
        artifactNodeList.forEach(mavenArtifactNode -> {
            MavenArtifact mavenArtifact = mavenArtifactNode.getArtifact();
            String artifactKey = this.getArtifactKey(mavenArtifact);
            List<MavenArtifactNode> mavenArtifactNodeList = artifactsMap.get(artifactKey);
            if (mavenArtifactNodeList == null) {
                List<MavenArtifactNode> artifactsMapValue = new ArrayList<>();
                artifactsMapValue.add(mavenArtifactNode);
                artifactsMap.put(artifactKey, artifactsMapValue);
            } else {
                mavenArtifactNodeList.add(mavenArtifactNode);
            }
            this.addArtifacts(artifactsMap, mavenArtifactNode.getDependencies(), i + 1);
        });
    }

    private String getArtifactKey(MavenArtifact mavenArtifact) {
        return mavenArtifact.getGroupId() + ":" + mavenArtifact.getArtifactId();
    }

    private boolean hasConflicts(List<MavenArtifactNode> mavenArtifactNodeList) {
        String version = null;
        for (MavenArtifactNode mavenArtifactNode : mavenArtifactNodeList) {
            MavenArtifact mavenArtifact = mavenArtifactNode.getArtifact();
            if (version == null) {
                version = mavenArtifact.getVersion();
                continue;
            }
            boolean conflict = version.equals(mavenArtifact.getVersion());
            if (!conflict) {
                return true;
            }
        }
        return false;
    }

    private String getMaxVersion(List<MavenArtifactNode> mavenArtifactNodeList) {
        String maxVersion = null;
        for (MavenArtifactNode mavenArtifactNode : mavenArtifactNodeList) {
            String version = mavenArtifactNode.getArtifact().getVersion();
            if (maxVersion == null) {
                maxVersion = version;
                continue;
            }
            if (this.compareVersion(maxVersion, version) == -1) {
                maxVersion = version;
            }
        }
        return maxVersion;
    }

    private int compareVersion(String version1, String version2) {
        if (version1.equals(version2)) {
            return 0;
        }
        String[] version1Array = version1.split("[._]");
        String[] version2Array = version2.split("[._]");
        int index = 0;
        int minLength = Math.min(version1.length(), version2.length());
        while (index < minLength) {
            if (isNumber(version1Array[index]) && isNumber(version2Array[index])) {
                int result = Integer.parseInt(version1Array[index]) - Integer.parseInt(version2Array[index]);
                if (result == 0) {
                    index++;
                    continue;
                }
                return result;
            }
            int result = version1Array[index].compareToIgnoreCase(version2Array[index]);
            if (result == 0) {
                index++;
                continue;
            }
            return result;
        }
        if (version1Array.length == version2Array.length) {
            return 0;
        }
        return version1Array.length > version2Array.length ? 1 : -1;
    }

    public static boolean isNumber(String str){
        Pattern pattern = Pattern.compile("[0-9]*");
        return pattern.matcher(str).matches();
    }
}
