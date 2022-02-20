package se.solrike.sonarlint.impl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.logging.Logger;
import org.sonarsource.sonarlint.core.StandaloneSonarLintEngineImpl;
import org.sonarsource.sonarlint.core.analysis.api.AnalysisResults;
import org.sonarsource.sonarlint.core.client.api.common.RuleKey;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneAnalysisConfiguration;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneGlobalConfiguration;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneSonarLintEngine;
import org.sonarsource.sonarlint.core.commons.Language;

import se.solrike.sonarlint.Sonarlint;
import se.solrike.sonarlint.SonarlintPlugin;

/**
 * @author Lucas Persson
 */
public class SonarlintAction {

  /**
   * Execute the task by calling to SonarLint engine. And generate reports.
   *
   * @param task
   *          - the gradle task
   */
  public List<IssueEx> run(Sonarlint task) {

    Logger logger = task.getLogger();

    return analyze(task, logger);

  }

  protected List<IssueEx> analyze(Sonarlint task, Logger logger) {

    Project project = task.getProject();
    Set<File> compileClasspath = task.getCompileClasspath().getFiles();
    Set<File> classFiles = task.getClassFiles().getFiles();
    Set<File> sourceFiles = task.getSource().getFiles();
    boolean isTestSource = task.isIsTestSource();
    Set<String> excludeRules = task.getExcludeRules().get();
    Set<String> includeRules = task.getIncludeRules().get();
    Map<String, Map<String, String>> ruleParameters = task.getRuleParameters().get();

    Map<String, String> sonarProperties = new HashMap<>(1);
    String libs = compileClasspath.stream().filter(File::exists).map(File::getPath).collect(Collectors.joining(","));
    sonarProperties.put("sonar.java.libraries", libs);
    String binaries = classFiles.stream().filter(File::exists).map(File::getPath).collect(Collectors.joining(","));
    sonarProperties.put("sonar.java.binaries", binaries);
    Configuration pluginConfiguration = project.getConfigurations().getByName(SonarlintPlugin.PLUGINS_CONFIG_NAME);
    Path[] plugins = pluginConfiguration.getFiles().stream().map(File::toPath).toArray(Path[]::new);

    if (isTestSource) {
      sonarProperties.put("sonar.java.test.libraries", libs);
      sonarProperties.put("sonar.java.test.binaries", binaries);
    }

    // Java sourceCompatibility
    String sourceCompatibility = project.getProperties().get("sourceCompatibility").toString();
    sonarProperties.put("sonar.java.source", sourceCompatibility);

    StandaloneGlobalConfiguration config = StandaloneGlobalConfiguration.builder()
        .addEnabledLanguages(Language.values())
        .addPlugins(plugins)
        .setLogOutput(new GradleClientLogOutput(logger))
        .setWorkDir(project.mkdir("build/sonarlint").toPath())
        // .setNodeJs(....., null)
        .setSonarLintUserHome(project.getBuildDir().toPath())
        .build();

    StandaloneSonarLintEngine engine = new StandaloneSonarLintEngineImpl(config);
    Path projectDir = project.getProjectDir().toPath();
    List<ClientInputFileImpl> fileList = sourceFiles.stream()
        .map(f -> new ClientInputFileImpl(projectDir, f.toPath(), isTestSource, StandardCharsets.UTF_8))
        .collect(Collectors.toList());

    StandaloneAnalysisConfiguration configuration = StandaloneAnalysisConfiguration.builder()
        .setBaseDir(project.getProjectDir().toPath())
        .addInputFiles(fileList)
        .addExcludedRules(getRuleKeys(excludeRules))
        .addIncludedRules(getRuleKeys(includeRules))
        .addRuleParameters(getRuleParameters(ruleParameters))
        .putAllExtraProperties(sonarProperties)
        .build();

    IssueCollector collector = new IssueCollector();
    AnalysisResults results = engine.analyze(configuration, collector, new GradleClientLogOutput(logger),
        new GradleProgressMonitor(logger));

    List<IssueEx> issues = collector.getIssues();
    issues.forEach(i -> i.setRulesDetails(engine.getRuleDetails(i.getRuleKey())));

    logger.debug("Files: {}", results.indexedFileCount());
    logger.debug("Issues: {}", issues);

    try {
      engine.stop();
    }
    catch (Exception e) {
      logger.warn("could not stop the engine");
    }

    return issues;
  }

  protected RuleKey[] getRuleKeys(Set<String> rules) {
    return rules.stream().map(RuleKey::parse).toArray(RuleKey[]::new);
  }

  protected Map<RuleKey, Map<String, String>> getRuleParameters(Map<String, Map<String, String>> ruleParameters) {
    return ruleParameters.entrySet()
        .stream()
        .collect(Collectors.toMap(rp -> RuleKey.parse(rp.getKey()), Entry<String, Map<String, String>>::getValue));
  }

}