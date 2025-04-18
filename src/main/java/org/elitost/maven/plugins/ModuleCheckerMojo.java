package org.elitost.maven.plugins;

import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.elitost.maven.plugins.checkers.*;
import org.elitost.maven.plugins.renderers.HtmlReportRenderer;
import org.elitost.maven.plugins.renderers.MarkdownReportRenderer;
import org.elitost.maven.plugins.renderers.ReportRenderer;
import org.elitost.maven.plugins.renderers.TextReportRenderer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Mojo(name = "check", defaultPhase = LifecyclePhase.NONE)
@Execute(goal = "check")
public class ModuleCheckerMojo extends AbstractMojo {

    @Parameter(property = "format", defaultValue = "html")
    private List<String> format;

    @Parameter(property = "checkersToRun")
    private List<String> checkersToRun;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepositories;

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Parameter
    private List<String> propertiesToCheck;

    private ExpectedModulesChecker expectedModulesChecker;
    private ParentVersionChecker parentChecker;
    private PropertyChecker propertyChecker;
    private HardcodedVersionChecker hardcodedChecker;
    private DependencyUpdateChecker updateChecker;
    private CommentedTagsChecker commentedTagsChecker;
    private RedundantPropertiesChecker redundantChecker;
    private UnusedDependenciesChecker unusedDependenciesChecker;
    private UrlChecker urlChecker;
    private RedefinedDependencyVersionChecker redefinitionChecker;


    private Log log;
    private boolean runAll;

    @Override
    public void execute() throws MojoExecutionException {
        this.log = getLog();

        if (!isParentPom()) {
            log.info("🔍 Ce n'est pas le pom parent, le plugin ne s'exécute pas ici.");
            return;
        }

        initCheckers();

        ReportRenderer renderer = resolveRenderer();

        runAll = checkersToRun == null || checkersToRun.isEmpty();

        enrichPropertiesFromSystem();
        logSelectedCheckers();

        String content = generateReportContent(project, renderer);

        List<MavenProject> modules = project.getCollectedProjects();
        if (modules != null) {
            for (MavenProject module : modules) {
                content += generateReportContent(module, renderer);
            }
        }

        writeReport(content);
    }

    private void initCheckers() {
        expectedModulesChecker = new ExpectedModulesChecker(log, resolveRenderer());
        parentChecker = new ParentVersionChecker(log, repoSystem, repoSession, remoteRepositories, resolveRenderer());
        propertyChecker = new PropertyChecker(log, resolveRenderer());
        hardcodedChecker = new HardcodedVersionChecker(log, resolveRenderer());
        updateChecker = new DependencyUpdateChecker(log, repoSystem, repoSession, remoteRepositories, resolveRenderer());
        commentedTagsChecker = new CommentedTagsChecker(log, resolveRenderer());
        redundantChecker = new RedundantPropertiesChecker(log, resolveRenderer());
        unusedDependenciesChecker = new UnusedDependenciesChecker(log, resolveRenderer());
        urlChecker = new UrlChecker(log, resolveRenderer());
        redefinitionChecker = new RedefinedDependencyVersionChecker(log, resolveRenderer());
    }

    private void enrichPropertiesFromSystem() {
        if (propertiesToCheck == null) {
            propertiesToCheck = new ArrayList<>();
        }

        String sysProp = System.getProperty("propertiesToCheck");
        if (sysProp != null && !sysProp.isEmpty()) {
            propertiesToCheck.addAll(Arrays.asList(sysProp.split(",")));
        } else {
            log.warn("⚠️ Aucune propriété à vérifier n'a été fournie via -DpropertiesToCheck.");
        }
    }

    private void logSelectedCheckers() {
        if (!runAll) {
            log.info("✅ Checkers explicitement demandés : " + String.join(", ", checkersToRun));
        }
    }

    private String generateReportContent(MavenProject module, ReportRenderer renderer) {
        StringBuilder content = new StringBuilder();
        content.append(renderer.renderHeader2("Module : " + module.getArtifactId()));

        if (isTopLevelProject(module) && (runAll || checkersToRun.contains("ExpectedModules"))) {
            content.append(expectedModulesChecker.generateModuleCheckReport(module)).append("\n");
        }

        if (runAll || checkersToRun.contains("parent")) {
            content.append(parentChecker.generateParentVersionReport(module)).append("\n");
        }

        if (isTopLevelProject(module) && (runAll || checkersToRun.contains("property"))) {
            content.append(propertyChecker.generatePropertiesCheckReport(module, propertiesToCheck)).append("\n");
        }

        if (isTopLevelProject(module) && (runAll || checkersToRun.contains("urls"))) {

            content.append(urlChecker.generateUrlCheckReport(module)).append("\n");
        }

        runCommonCheckers(module, renderer, content);

        return content.toString();
    }

    private void runCommonCheckers(MavenProject module, ReportRenderer renderer, StringBuilder content) {
        if (runAll || checkersToRun.contains("hardcoded")) {
            content.append(hardcodedChecker.generateHardcodedVersionReport(module)).append("\n");
        }

        if (runAll || checkersToRun.contains("outdated")) {
            content.append(updateChecker.generateOutdatedDependenciesReport(module.getOriginalModel().getDependencies()));
        }

        if (runAll || checkersToRun.contains("commented")) {
            content.append(commentedTagsChecker.generateCommentedTagsReport(module));
        }

        if (runAll || checkersToRun.contains("redundant")) {
            content.append(redundantChecker.generateRedundantPropertiesReport(module)).append("\n");
        }

        if (runAll || checkersToRun.contains("usage")) {
            content.append(unusedDependenciesChecker.generateReport(module)).append("\n");
        }

        if (runAll || checkersToRun.contains("redefinition")) {
            content.append(redefinitionChecker.generateRedefinitionReport(module)).append("\n");
        }
    }

    private void writeReport(String content) throws MojoExecutionException {
        String ext = format != null && !format.isEmpty() ? format.get(0).toLowerCase() : "md";

        if (ext.equals("markdown")) ext = "md";
        if (ext.equals("html")) ext = "html";

        File file = new File(project.getBasedir(), "module-check-report." + ext);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            if (ext.equals("md")) {
                writer.write("# Rapport de Vérification\n\n");
                writer.write(content);
            } else if (ext.equals("text")) {
                writer.write(content);
            } else if (ext.equals("html")) {
                // Lecture du fichier style.css depuis les ressources
                String cssContent = readResourceAsString("assets/css/style.css");

                writer.write("<html>\n<head>\n<title>Rapport de Vérification</title>\n");
                writer.write("<style>\n" + cssContent + "\n</style>\n");
                writer.write("</head>\n<body>\n");
                writer.write("<h1>Rapport de Vérification</h1>\n");
                writer.write(content);
                writer.write("</body>\n</html>");
            }

        } catch (IOException e) {
            throw new MojoExecutionException("❌ Erreur lors de la création du fichier de rapport", e);
        }

        log.info("📄 Rapport global généré : " + file.getAbsolutePath());
        log.info("Vous pouvez consulter le rapport ici : file://" + file.getAbsolutePath());
    }

    private String readResourceAsString(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new FileNotFoundException("Le fichier de ressource '" + path + "' est introuvable dans le classpath.");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean isParentPom() {
        return project.getModules() != null && !project.getModules().isEmpty();
    }

    private boolean isTopLevelProject(MavenProject module) {
        return module.getArtifactId().equals(project.getArtifactId());
    }

    ReportRenderer resolveRenderer() {
        String firstFormat = format != null && !format.isEmpty() ? format.get(0) : "markdown";
        String lowerFormat = firstFormat.toLowerCase();

        switch (lowerFormat) {
            case "html":
                return new HtmlReportRenderer();
            case "text":
                return new TextReportRenderer();
            case "markdown":
            case "md":
                return new MarkdownReportRenderer();
            default:
                log.warn("Format inconnu '" + firstFormat + "', utilisation de Markdown par défaut.");
                return new MarkdownReportRenderer();
        }
    }

}