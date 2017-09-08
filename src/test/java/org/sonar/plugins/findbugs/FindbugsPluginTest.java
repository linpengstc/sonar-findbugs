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

import org.junit.Test;
import org.sonar.api.Plugin;
import org.sonar.api.utils.Version;

import java.io.*;
import java.net.URL;

import static junit.framework.TestCase.assertEquals;

public class FindbugsPluginTest {

  @Test
  public void testGetExtensions() {

    Plugin.Context ctx = new FindbugsPlugin.Context(Version.parse("1.33.7"));
    String classFilePath = this.getClass().getName().replaceAll("\\.", "/") + ".class";
    System.out.println(classFilePath);
    URL recource = this.getClass().getClassLoader().getResource(classFilePath);
//    System.out.println();
    System.out.println(recource.getPath().replace(classFilePath,""));
    String p = recource.getPath().replace(classFilePath,"");
    File f = new File( p, "../sonar-findbugs-plugin.jar" );
    System.out.println(f.toPath());
    try {
      FileInputStream fo = new FileInputStream(f);
      System.out.println(fo.read());
    } catch (Exception e) {
      e.printStackTrace();
    }
    FindbugsPlugin plugin = new FindbugsPlugin();
    plugin.define(ctx);

    assertEquals("extension count", 23, ctx.getExtensions().size());
  }

}
