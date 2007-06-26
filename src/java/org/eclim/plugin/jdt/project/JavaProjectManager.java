/**
 * Copyright (c) 2005 - 2006
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eclim.plugin.jdt.project;

import java.io.FileInputStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.collections.CollectionUtils;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import org.apache.commons.lang.StringUtils;

import org.eclim.Services;

import org.eclim.command.CommandLine;
import org.eclim.command.Error;
import org.eclim.command.Options;

import org.eclim.plugin.jdt.PluginResources;

import org.eclim.plugin.jdt.util.JavaUtils;

import org.eclim.plugin.jdt.project.classpath.Dependency;
import org.eclim.plugin.jdt.project.classpath.Parser;

import org.eclim.project.ProjectManager;

import org.eclim.util.ProjectUtils;
import org.eclim.util.XmlUtils;

import org.eclim.util.file.FileOffsets;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;

import org.eclipse.core.runtime.Path;

import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaModelStatus;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaConventions;
import org.eclipse.jdt.core.JavaCore;

import org.eclipse.jdt.internal.ui.wizards.ClassPathDetector;

import org.eclipse.jdt.launching.JavaRuntime;

/**
 * Implementation of {@link ProjectManager} for java projects.
 *
 * @author Eric Van Dewoestine (ervandew@yahoo.com)
 * @version $Revision$
 */
public class JavaProjectManager
  implements ProjectManager
{
  private static final Pattern STATUS_PATTERN =
    Pattern.compile(".*[\\\\/](.*)'.*");

  private static final String PRESERVE = "eclim.preserve";

  private static final String CLASSPATH_XSD =
    "/resources/schema/eclipse/classpath.xsd";

  /**
   * {@inheritDoc}
   */
  public void create (IProject _project, CommandLine _commandLine)
    throws Exception
  {
    String depends = _commandLine.getValue(Options.DEPENDS_OPTION);
    create(_project, depends);
  }

  /**
   * {@inheritDoc}
   */
  public Error[] update (IProject _project, CommandLine _commandLine)
    throws Exception
  {
    String buildfile = _commandLine.getValue(Options.BUILD_FILE_OPTION);

    IJavaProject javaProject = JavaUtils.getJavaProject(_project);
    javaProject.getResource().refreshLocal(IResource.DEPTH_INFINITE, null);

    // validate that .classpath xml is well formed and valid.
    String dotclasspath = javaProject.getProject().getFile(".classpath")
      .getRawLocation().toOSString();
    PluginResources resources = (PluginResources)
      Services.getPluginResources(PluginResources.NAME);
    Error[] errors = XmlUtils.validateXml(
        javaProject.getProject().getName(),
        dotclasspath,
        resources.getResource(CLASSPATH_XSD).toString());
    if(errors.length > 0){
      return errors;
    }

    // ivy.xml, etc updated.
    if(buildfile != null){
      String filename = FilenameUtils.getName(buildfile);
      Parser parser = (Parser)Services.getService(filename, Parser.class);
      IClasspathEntry[] entries = merge(javaProject, parser.parse(buildfile));
      errors = setClasspath(javaProject, entries, dotclasspath);

    // .classpath updated.
    }else{
      IClasspathEntry[] entries = javaProject.readRawClasspath();
      errors = setClasspath(javaProject, entries, dotclasspath);
    }

    if(errors.length > 0){
      return errors;
    }
    return null;
  }

  /**
   * {@inheritDoc}
   */
  public void refresh (IProject _project, CommandLine _commandLine)
    throws Exception
  {
    IJavaProject javaProject = JavaUtils.getJavaProject(_project);
    javaProject.getResource().refreshLocal(IResource.DEPTH_INFINITE, null);
  }

  /**
   * {@inheritDoc}
   */
  public void delete (IProject _project, CommandLine _commandLine)
    throws Exception
  {
  }

// Project creation methods

  /**
   * Creates a new project.
   *
   * @param _project The project.
   * @param _depends Comma seperated project names this project depends on.
   */
  protected void create (IProject _project, String _depends)
    throws Exception
  {
    IJavaProject javaProject = JavaCore.create(_project);
    ClassPathDetector detector = new ClassPathDetector(_project, null);
    IClasspathEntry[] detected = detector.getClasspath();
    IClasspathEntry[] depends =
      createOrUpdateDependencies(javaProject, _depends);
    IClasspathEntry[] container = new IClasspathEntry[]{
      JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER))
    };

    IClasspathEntry[] classpath = merge(
        new IClasspathEntry[][]{detected, depends, container});
          //javaProject.readRawClasspath(), detected, depends, container

    javaProject.setRawClasspath(classpath, null);
    javaProject.makeConsistent(null);
    javaProject.save(null, false);
  }

  /**
   * Creates or updates the projects dependecies other other projects.
   *
   * @param _project The project.
   * @param _depends The comma seperated list of project names.
   */
  protected IClasspathEntry[] createOrUpdateDependencies (
      IJavaProject _project, String _depends)
    throws Exception
  {
    if(_depends != null){
      String[] dependPaths = StringUtils.split(_depends, ',');
      IClasspathEntry[] entries = new IClasspathEntry[dependPaths.length];
      for(int ii = 0; ii < dependPaths.length; ii++){
        IProject theProject = ProjectUtils.getProject(dependPaths[ii]);
        if(!theProject.exists()){
          throw new IllegalArgumentException(Services.getMessage(
              "project.depends.not.found", dependPaths[ii]));
        }
        IJavaProject otherProject = JavaCore.create(theProject);
        entries[ii] = JavaCore.newProjectEntry(otherProject.getPath(), true);
      }
      return entries;
    }
    return new IClasspathEntry[0];
  }

  /**
   * Merges the supplied classpath entries into one.
   *
   * @param _entries The array of classpath entry arrays to merge.
   *
   * @return The union of all entry arrays.
   */
  protected IClasspathEntry[] merge (IClasspathEntry[][] _entries)
  {
    Collection union = new ArrayList();
    if(_entries != null){
      for(int ii = 0; ii < _entries.length; ii++){
        if(_entries[ii] != null){
          union = CollectionUtils.union(union, Arrays.asList(_entries[ii]));
        }
      }
    }
    return (IClasspathEntry[])union.toArray(new IClasspathEntry[union.size()]);
  }

// Project update methods

  /**
   * Sets the classpath for the supplied project.
   *
   * @param _javaProject The project.
   * @param _entries The classpath entries.
   * @param _classpath The file path of the .classpath file.
   * @return Array of Error or null if no errors reported.
   */
  protected Error[] setClasspath (
      IJavaProject _javaProject, IClasspathEntry[] _entries, String _classpath)
    throws Exception
  {
    FileOffsets offsets = FileOffsets.compile(_classpath);
    String classpath = IOUtils.toString(new FileInputStream(_classpath));
    List errors = new ArrayList();
    for(int ii = 0; ii < _entries.length; ii++){
      IJavaModelStatus status = JavaConventions.validateClasspathEntry(
          _javaProject, _entries[ii], true);
      if(!status.isOK()){
        errors.add(createErrorFromStatus(offsets, _classpath, classpath, status));
      }
    }

    IJavaModelStatus status = JavaConventions.validateClasspath(
        _javaProject, _entries, _javaProject.getOutputLocation());

    // always set the classpath anyways, so that the user can correct the file.
    //if(status.isOK() && errors.isEmpty()){
      _javaProject.setRawClasspath(_entries, null);
      _javaProject.makeConsistent(null);
    //}

    if(!status.isOK()){
      errors.add(createErrorFromStatus(offsets, _classpath, classpath, status));
    }
    return (Error[])errors.toArray(new Error[errors.size()]);
  }

  /**
   * Creates an Error from the supplied IJavaModelStatus.
   *
   * @param _offsets File offsets for the classpath file.
   * @param _filename The filename of the error.
   * @param _contents The contents of the file as a String.
   * @param _status The IJavaModelStatus.
   * @return The Error.
   */
  protected Error createErrorFromStatus (
      FileOffsets _offsets, String _filename, String _contents, IJavaModelStatus _status)
    throws Exception
  {
    int line = 0;
    int col = 0;

    // get the pattern to search for from the status message.
    Matcher matcher = STATUS_PATTERN.matcher(_status.getMessage());
    String pattern = matcher.replaceFirst("$1");

    // find the pattern in the classpath file.
    matcher = Pattern.compile("\\Q" + pattern + "\\E").matcher(_contents);
    if(matcher.find()){
      int[] position = _offsets.offsetToLineColumn(matcher.start());
      line = position[0];
      col = position[1];
    }

    return new Error(_status.getMessage(), _filename, line, col, false);
  }

  /**
   * Merges the supplied project's classpath with the specified dependencies.
   *
   * @param _project The project.
   * @param _dependencies The dependencies.
   * @return The classpath entries.
   */
  protected IClasspathEntry[] merge (
      IJavaProject _project, Dependency[] _dependencies)
    throws Exception
  {
    IWorkspaceRoot root = _project.getProject().getWorkspace().getRoot();
    Collection results = new ArrayList();

    // load the results with all the non library entries.
    IClasspathEntry[] entries = _project.getRawClasspath();
    for(int ii = 0; ii < entries.length; ii++){
      if (entries[ii].getEntryKind() != IClasspathEntry.CPE_LIBRARY &&
          entries[ii].getEntryKind() != IClasspathEntry.CPE_VARIABLE)
      {
        results.add(entries[ii]);
      } else if (preserve(entries[ii])){
        results.add(entries[ii]);
      }
    }

    // merge the dependencies with the classpath entires.
    for(int ii = 0; ii < _dependencies.length; ii++){
      IClasspathEntry match = null;
      for(int jj = 0; jj < entries.length; jj++){
        if (entries[jj].getEntryKind() == IClasspathEntry.CPE_LIBRARY ||
            entries[jj].getEntryKind() == IClasspathEntry.CPE_VARIABLE)
        {
          String path = entries[jj].getPath().toOSString();
          String pattern = _dependencies[ii].getName() +
            Dependency.VERSION_SEPARATOR;

          // exact match
          if(path.endsWith(_dependencies[ii].toString())){
            match = entries[jj];
            results.add(entries[jj]);
            break;

          // different version match
          }else if(path.indexOf(pattern) != -1){
            break;
          }
        }else if(entries[jj].getEntryKind() == IClasspathEntry.CPE_PROJECT){
          String path = entries[jj].getPath().toOSString();
          if(path.endsWith(_dependencies[ii].getName())){
            match = entries[jj];
            break;
          }
        }
      }

      if(match == null){
        IClasspathEntry entry = createEntry(root, _project, _dependencies[ii]);
        results.add(entry);
      }else{
        match = null;
      }
    }

    return (IClasspathEntry[])
      results.toArray(new IClasspathEntry[results.size()]);
  }

  /**
   * Determines if the supplied entry contains attribute indicating that it
   * should not be removed.
   *
   * @param entry The IClasspathEntry
   * @return true to preserve the entry, false otherwise.
   */
  protected boolean preserve (IClasspathEntry entry)
  {
    IClasspathAttribute[] attributes = entry.getExtraAttributes();
    for(int ii = 0; ii < attributes.length; ii++){
      String name = attributes[ii].getName();
      if(PRESERVE.equals(name)){
        return Boolean.parseBoolean(attributes[ii].getValue());
      }
    }
    return false;
  }

  /**
   * Creates the classpath entry.
   *
   * @param _root The workspace root.
   * @param _project The project to create the dependency in.
   * @param _dependency The dependency to create the entry for.
   * @return The classpath entry.
   */
  protected IClasspathEntry createEntry (
      IWorkspaceRoot _root, IJavaProject _project, Dependency _dependency)
    throws Exception
  {
    if(_dependency.isVariable()){
      return JavaCore.newVariableEntry(_dependency.getPath(), null, null, true);
    }

    return JavaCore.newLibraryEntry(_dependency.getPath(), null, null, true);
  }

  /**
   * Determines if the supplied path starts with a variable name.
   *
   * @param _path The path to test.
   * @return True if the path starts with a variable name, false otherwise.
   */
  protected boolean startsWithVariable (String _path)
  {
    String[] variables = JavaCore.getClasspathVariableNames();
    for(int ii = 0; ii < variables.length; ii++){
      if(_path.startsWith(variables[ii])){
        return true;
      }
    }
    return false;
  }
}
