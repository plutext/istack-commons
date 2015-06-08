/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.istack.soimp;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.taskdefs.Copy;
import org.apache.tools.ant.taskdefs.Delete;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.BuildException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Subversion overwriting import tool.
 *
 * @author Kohsuke Kawaguchi
 */
public class Soimp extends Task {

    public static void main(String[] args) throws IOException, ProcessingException {
        System.exit(new Soimp().runCLI(args));
    }

    // either this, or ...
    @Argument
    List<String> pathArgs = new ArrayList<String>();
    // these (for Ant)
    private File wsDir;
    private String remoteURL;

    @Option(name = "-x", usage="specifies the svn command executable to run")
    String svn = "svn";

    @Option(name="-m", usage="specifies the commit message")
    String commitMessage = "imported by soimp";

    @Option(name="-p", usage="create the repository by 'svn mkdir' if necessary")
    boolean create = false;

    @Option(name="-u", usage="specify a username")
    String username = null;

    @Option(name="-P", usage="specify a password")
    String password = null;

    @Option(name="-o", usage="provide additional options for 'svn' command")
    String additionalOptions = null;

    private Listener listener = Listener.CONSOLE;

    public void setSvn(String svn) {
        this.svn = svn;
    }

    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public void setCreate(boolean create) {
        this.create = create;
    }

    public void setUsername(String userName) {
        this.username = userName;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setDir(File dir) {
        wsDir = dir;
    }

    public void setRepository(String repository) {
        this.remoteURL = repository;
    }

    public void setAdditionalOptions(String additionalOptions) {
        this.additionalOptions = additionalOptions;
    }

    /**
     * Command line tool entry point.
     *
     * @return
     *      0 if success. Non-zero if failed.
     */
    public int runCLI(String[] args) throws IOException, ProcessingException {
        CmdLineParser p = new CmdLineParser(this);
        try {
            p.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            printUsage(p);
            return -1;
        }

        if(pathArgs.size()>2 || pathArgs.isEmpty()) {
            printUsage(p);
            return -1;
        }

        File src = new File(pathArgs.get(0));
        if(!src.exists()) {
            System.err.println("No such directory "+src);
            return -1;
        }

        if(pathArgs.size()==1) {
            // just one argument. simply update the state of the existing workspace
            svnUpdate(src);
        } else {
            // two arguments
            // checkout -> copy -> import -> commit
            svnImport(src,pathArgs.get(1));
        }

        return 0;
    }

    /**
     * Ant entry point.
     */
    @Override
    public void execute() throws BuildException {
        String svnExe = getProject().getProperty("svn.executable");
        if(svnExe!=null)
            svn = svnExe;

        listener = new Listener() {
            public void info(String line) {
                log(line,Project.MSG_INFO);
            }
        };
        if(wsDir==null)
            throw new BuildException("Required @dir is missing");
        if(!wsDir.exists())
            throw new BuildException("No such directory "+wsDir);

        try {
            if(remoteURL==null)
                svnUpdate(wsDir);
            else
                svnImport(wsDir,remoteURL);
        } catch (ProcessingException e) {
            throw new BuildException(e);
        } catch (IOException e) {
            throw new BuildException(e);
        }
    }

    private void svnImport(File src, String repository) throws IOException, ProcessingException {
        File tmp = File.createTempFile("soimp","tmp");
        boolean deleted = tmp.delete();
        if (!deleted) {
            Logger.getLogger(this.getClass().getName()).log(Level.FINE, "Cannot delete file: {0}", tmp);
        }
        boolean created = tmp.mkdir();
        if (!created) {
            Logger.getLogger(this.getClass().getName()).log(Level.FINE, "Cannot create directory: {0}", tmp);
        }
        listener.info("Using "+tmp);

        Project p = new Project(); // dummy ant projcet

        try {
            // check if the directory exists
            if(create)
                createRepository(new URL(repository));

            // check out to tmp
            exec(buildSvnWithUsername("co "+repository+" ."),tmp,"failed to check out");

            FileSet fs = new FileSet();
            fs.setProject(p);
            fs.setDir(src);

            // copy all files from src -> tmp
            Copy cpTask = new Copy();
            cpTask.setProject(p);
            cpTask.setOverwrite(true);
            cpTask.setTodir(tmp);
            cpTask.addFileset(fs);
            cpTask.execute();

            // update
            svnUpdate(tmp);

            // then commit
            exec(buildSvnWithUsername("commit -m \""+commitMessage+"\""),tmp,"Failed to commit");
        } finally{
            // clean up
            Delete delTask = new Delete();
            delTask.setProject(p);
            delTask.setDir(tmp);
            delTask.execute();
        }
    }

    /**
     * Creates directories in the repository if necessary
     */
    private void createRepository(URL repository) throws IOException, ProcessingException {
        if(repository.getPath().equals("/"))
            throw new ProcessingException("Illegal repository name");
        try {
            exec(buildSvnWithUsername("proplist "+repository),new File("."),"N/A");
        } catch (ProcessingException e) {
            // directory doesn't exist
            URL parent;
            if(repository.getPath().endsWith("/"))
                parent = new URL(repository, "..");
            else
                parent = new URL(repository, ".");
            createRepository(parent);

            listener.info(repository+" doesn't exist. creating");
            exec(buildSvnWithUsername("mkdir -m \""+commitMessage+"\" "+repository),new File("."),"Failed to create directory");
        }
    }

    private void printUsage(CmdLineParser p) {
        System.err.println("Usage: soimp [options ...] <PATH> <URL>");
        System.err.println("Works like 'svn import PATH URL', but overwrites remote files by local files.");
        p.printUsage(System.err);
    }

    public void svnUpdate( File ws ) throws ProcessingException, IOException {

        String log = exec(buildSvnWithUsername("status"), ws, "Failed to stat the workspace");

        List<String> newFiles = new ArrayList<String>();
        List<String> deletedFiles = new ArrayList<String>();
        // % svn status
        // ?      xyz
        // M      abc
        // !      def
        BufferedReader in = new BufferedReader(new StringReader(log));
        String line;
        while((line=in.readLine())!=null) {
            Mode m = Mode.parse(line.charAt(0));
            String file = line.substring(1).trim();
            if(m==null)     continue;

            switch(m) {
            case NEW:
                newFiles.add(file);
                break;
            case DELETED:
                deletedFiles.add(file);
                break;
            default:
                break;
            }
        }

        // update accordingly
        runSvnBatch("add",ws,newFiles);
        runSvnBatch("remove",ws,deletedFiles);
    }

    /**
     * Run svn command in a batch.
     */
    private void runSvnBatch(String subCmd, File ws, List<String> files) throws IOException, ProcessingException {
        while(!files.isEmpty()) {
            // build up command line
            StringBuilder cmd = new StringBuilder(buildSvnCommand(subCmd));
            for( int i=0; i<20 && !files.isEmpty(); i++ ) {
                cmd.append(' ').append(files.remove(0));
            }

            exec(cmd.toString(),ws,"Failed to "+subCmd);
        }
    }

    /**
     * Concatenate svn executable name with svn global options (username/password)
     * and the svn command.
     *
     * @param subCmd The svn command
     * @return The complete executable command
     */
    private String buildSvnWithUsername(final String subCmd) {
        final StringBuilder command = new StringBuilder();
        if (username != null) {
            command.append("--username ").append(username).append(' ');
        }
        if (password != null) {
            command.append("--password ").append(password).append(' ');
        }
        command.append(subCmd);
        return buildSvnCommand(command.toString());
    }

    /**
     * Concatenate svn executable name with the svn command.
     *
     * @param subCmd The svn command
     * @return The complete executable command
     */
    private String buildSvnCommand(final String subCmd) {
        final StringBuilder command = new StringBuilder(svn + ' ');
        addAdditionalOptionsToCommand(command);
        command.append(subCmd);
        return command.toString();
    }

    private void addAdditionalOptionsToCommand(final StringBuilder command) {
        if (additionalOptions != null) {
            command.append(additionalOptions);
            command.append(' ');
        }
    }

    /**
     * Executes subversion and returns its output.
     */
    private String exec(String cmd, File ws, String errorMessage) throws IOException, ProcessingException {
        listener.info("Executing: "+cmd);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int r = new Proc(cmd, null, baos, ws).join();
        if(r!=0) {
            listener.info(baos.toString());
            throw new ProcessingException(errorMessage);
        }
        return baos.toString();
    }

}
