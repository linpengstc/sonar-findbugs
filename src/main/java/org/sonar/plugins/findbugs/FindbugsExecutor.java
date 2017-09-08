/*
 * SonarQube Findbugs Plugin
 * Copyright (C) 2012 SonarSource
 * sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.findbugs;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.FindBugs;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.Plugin;
import edu.umd.cs.findbugs.PluginException;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.XMLBugReporter;
import edu.umd.cs.findbugs.config.UserPreferences;
import edu.umd.cs.findbugs.plugins.DuplicatePluginIdException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.BatchSide;
import org.sonar.api.utils.SonarException;
import org.sonar.api.utils.TimeProfiler;
import org.sonar.api.utils.log.Profiler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@BatchSide
public class FindbugsExecutor {

  private static final String FINDBUGS_CORE_PLUGIN_ID = "edu.umd.cs.findbugs.plugins.core";

  private static final Logger LOG = LoggerFactory.getLogger(FindbugsExecutor.class);

  /**
   * Map of priority level names to their numeric values.
   */
  private static Map<String, Integer> priorityNameToValueMap = new HashMap<String, Integer>();

  static {
    priorityNameToValueMap.put("high", Priorities.HIGH_PRIORITY);
    priorityNameToValueMap.put("medium", Priorities.NORMAL_PRIORITY);
    priorityNameToValueMap.put("low", Priorities.LOW_PRIORITY);
    priorityNameToValueMap.put("experimental", Priorities.EXP_PRIORITY);
  }

  private static final Integer DEFAULT_PRIORITY = Priorities.NORMAL_PRIORITY;

  private final FindbugsConfiguration configuration;

  public FindbugsExecutor(FindbugsConfiguration configuration) {
    this.configuration = configuration;
  }

  @VisibleForTesting
  Collection<ReportedBug> execute() {
    return execute(true);
  }

  public Collection<ReportedBug> execute(boolean useAllPlugin) {
    return execute(useAllPlugin,useAllPlugin,useAllPlugin);
  }

  public Collection<ReportedBug> execute(boolean useFbContrib, boolean useFindSecBugs, boolean uselpSec) {
    // We keep a handle on the current security manager because FB plays with it and we need to restore it before shutting down the executor
    // service
    SecurityManager currentSecurityManager = System.getSecurityManager();
    ClassLoader initialClassLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(FindBugs2.class.getClassLoader());

    // This is a dirty workaround, but unfortunately there is no other way to make Findbugs generate english messages only - see SONARJAVA-380
    Locale initialLocale = Locale.getDefault();
    Locale.setDefault(Locale.ENGLISH);

    OutputStream xmlOutput = null;
    Collection<Plugin> customPlugins = null;
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    try {
      final FindBugs2 engine = new FindBugs2();

      Project project = configuration.getFindbugsProject();

      if(project.getFileCount() == 0) {
        LOG.info("Findbugs analysis skipped for this project.");
        return new ArrayList<>();
      }

      customPlugins = loadFindbugsPlugins(useFbContrib,useFindSecBugs, uselpSec);

      disableUpdateChecksOnEveryPlugin();

      engine.setProject(project);

      XMLBugReporter xmlBugReporter = new XMLBugReporter(project);
      xmlBugReporter.setPriorityThreshold(determinePriorityThreshold());
      xmlBugReporter.setAddMessages(true);

      File xmlReport = configuration.getTargetXMLReport();
      LOG.info("Findbugs output report: " + xmlReport.getAbsolutePath());
      xmlOutput = FileUtils.openOutputStream(xmlReport);
      xmlBugReporter.setOutputStream(new PrintStream(xmlOutput));

      engine.setBugReporter(xmlBugReporter);

      UserPreferences userPreferences = UserPreferences.createDefaultUserPreferences();
      userPreferences.setEffort(configuration.getEffort());
      engine.setUserPreferences(userPreferences);

      engine.addFilter(configuration.saveIncludeConfigXml().getAbsolutePath(), true);

      for (File filterFile : configuration.getExcludesFilters()) {
        if (filterFile.isFile()) {
          LOG.info("Use filter-file: {}", filterFile);
          engine.addFilter(filterFile.getAbsolutePath(), false);
        } else {
          LOG.warn("FindBugs filter-file not found: {}", filterFile);
        }
      }

      engine.setDetectorFactoryCollection(DetectorFactoryCollection.instance());
      engine.setAnalysisFeatureSettings(FindBugs.DEFAULT_EFFORT);

      engine.finishSettings();

      executorService.submit(new FindbugsTask(engine)).get(configuration.getTimeout(), TimeUnit.MILLISECONDS);

      return toReportedBugs(xmlBugReporter.getBugCollection());
    } catch (TimeoutException e) {
      throw new IllegalStateException("Can not execute Findbugs with a timeout threshold value of " + configuration.getTimeout() + " milliseconds", e);
    } catch (Exception e) {
      throw new IllegalStateException("Can not execute Findbugs", e);
    } finally {
      // we set back the original security manager BEFORE shutting down the executor service, otherwise there's a problem with Java 5
      System.setSecurityManager(currentSecurityManager);
      resetCustomPluginList(customPlugins);
      executorService.shutdown();
      IOUtils.closeQuietly(xmlOutput);
      Thread.currentThread().setContextClassLoader(initialClassLoader);
      Locale.setDefault(initialLocale);
    }
  }

  private static Collection<ReportedBug> toReportedBugs(BugCollection bugCollection) {
    // We need to retrieve information such as the message before we shut everything down as we will lose any custom
    // bug messages
    final Collection<ReportedBug> bugs = new ArrayList<ReportedBug>();

    for (final BugInstance bugInstance : bugCollection) {
      if (bugInstance.getPrimarySourceLineAnnotation() == null) {
        LOG.warn("No source line for " + bugInstance.getType());
        continue;
      }

      bugs.add(new ReportedBug(bugInstance));
    }
    return bugs;
  }

  private Integer determinePriorityThreshold() {
    Integer integer = priorityNameToValueMap.get(configuration.getConfidenceLevel());
    if (integer == null) {
      integer = DEFAULT_PRIORITY;
    }
    return integer;
  }

  private static class FindbugsTask implements Callable<Object> {

    private final FindBugs2 engine;

    public FindbugsTask(FindBugs2 engine) {
      this.engine = engine;
    }

    @Override
    public Object call() {
      try {
        engine.execute();
        return null;
      } catch (InterruptedException | IOException e) {
        throw Throwables.propagate(e);
      } finally {
        engine.dispose();
      }
    }
  }

  private Collection<Plugin> loadFindbugsPlugins(boolean useFbContrib,boolean useFindSecBugs, boolean uselpSec) {
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

    List<String> pluginJarPathList = Lists.newArrayList();
    try {
      Enumeration<URL> urls = contextClassLoader.getResources("findbugs.xml");
      while (urls.hasMoreElements()) {
        URL url = urls.nextElement();
        pluginJarPathList.add(normalizeUrl(url));
      }
      //Add fb-contrib plugin.
      if (useFbContrib && configuration.getFbContribJar() != null) {
        // fb-contrib plugin is packaged by Maven. It is not available during execution of unit tests.
        pluginJarPathList.add(configuration.getFbContribJar().getAbsolutePath());
      }
      //Add find-sec-bugs plugin. (same as fb-contrib)
      if (useFindSecBugs && configuration.getFindSecBugsJar() != null) {
        pluginJarPathList.add(configuration.getFindSecBugsJar().getAbsolutePath());
      }

      if (uselpSec && configuration.getLpSecBugsJar() != null) {
        pluginJarPathList.add(configuration.getLpSecBugsJar().getAbsolutePath());
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
    List<Plugin> customPluginList = Lists.newArrayList();

    for (String path : pluginJarPathList) {
      try {
        Plugin plugin = Plugin.addCustomPlugin(new File(path).toURI(), contextClassLoader);
        if (plugin != null) {
          customPluginList.add(plugin);
          LOG.info("Loading findbugs plugin: " + path);
        }
      } catch (PluginException e) {
        LOG.warn("Failed to load plugin for custom detector: " + path);
        LOG.debug("Cause of failure", e);
      } catch (DuplicatePluginIdException e) {
        // FB Core plugin is always loaded, so we'll get an exception for it always
        if (!FINDBUGS_CORE_PLUGIN_ID.equals(e.getPluginId())) {
          // log only if it's not the FV Core plugin
          LOG.debug("Plugin already loaded: exception ignored: " + e.getMessage(), e);
        }
      }
    }

    return customPluginList;
  }

  private static String normalizeUrl(URL url) throws URISyntaxException {
    return StringUtils.removeStart(StringUtils.substringBefore(url.toURI().getSchemeSpecificPart(), "!"), "file:");
  }

  /**
   * Disable the update check for every plugin. See http://findbugs.sourceforge.net/updateChecking.html
   */
  private static void disableUpdateChecksOnEveryPlugin() {
    for (Plugin plugin : Plugin.getAllPlugins()) {
      plugin.setMyGlobalOption("noUpdateChecks", "true");
    }
  }

  private static void resetCustomPluginList(Collection<Plugin> customPlugins) {
    if (customPlugins != null) {
      for (Plugin plugin : customPlugins) {
        Plugin.removeCustomPlugin(plugin);
      }
    }
  }

}
