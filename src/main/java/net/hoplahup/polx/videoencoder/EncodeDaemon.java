package net.hoplahup.polx.videoencoder;

import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * A process meant to run in the background to call the encoder everytime
 * it is needed.
 * This process maps a folder to another folder and employs witness-files
 * at the target to detect if a file needs to be processed.
 *
 * Invoke with the encode-daemon.properties configuration in
 * /etc/ or in current directory.
 *
 * The process will run indefinitely. It will launch videoEncoder processes
 * that run in a sub-folder of the temp directory as working directory and log.
 */

public class EncodeDaemon implements Runnable {

    public static void main(String[] args) throws Exception {
        boolean helpNeeded = false;

        if(args.length==1 && "-sampleConfig".equals(args[0])) {
            IOUtils.copy(EncodeDaemon.class.getResourceAsStream("sample-daemon-config.properties"), System.out);
            return;
        }

        for(String arg: args) if("--help".equals(arg.toLowerCase())) helpNeeded = true;
        if(findConfigFile()==null) helpNeeded = true;
        if(helpNeeded) {
            System.err.println("Usage java -classpath videoEncoder.jar net.hoplahup.polx.videoencoder.EncodeDaemon \n" +
                    "  After having put the configuration encode-daemon.properties in the current directory or in /etc/.\n" +
                    "  Please invoke: \n" +
                    "    java -classpath videoEncoder.jar net.hoplahup.polx.videoencoder.EncodeDaemon -sampleConfig \n" +
                    "  to obtain a suggested configuration. \n");
        } else {
            EncodeDaemon e = new EncodeDaemon();
            e.run();
        }
    }

    private static File findConfigFile() {
        File f = new File("/etc/encode-daemon.properties");
        if(f.isFile()) return f;
        f = new File("encode-daemon.properties");
        if(f.isFile()) return f;
        return null;
    }

    private void startProcess(File sourceFile, File destDir) throws Exception {
        System.out.println("Starting process " + sourceFile + " to " + destDir);
        File myTemp = new File(tempDir, df.format(new Date()) + FilenameUtils.getBaseName(sourceFile.getName()).replaceAll(" ", "_"));
        myTemp.mkdirs();
        DefaultExecutor executor = new DefaultExecutor();
        String baseName = FilenameUtils.getBaseName(sourceFile.getName());
        destDir = new File(destDir, baseName); destDir.mkdirs();
        String prefixHere = pathPrefix + sourceFile.getParentFile().getAbsolutePath().substring(srcDir.getAbsolutePath().length()+1) + "/" + baseName + "/";
        PrintStream debugStream = new PrintStream(new FileOutputStream(new File(myTemp, "debug.log")));
        executor.setStreamHandler(new PumpStreamHandler(debugStream, debugStream));
        executor.setWorkingDirectory(myTemp);
        executor.execute(Util.cli("java " +
                " -Dencoder.tmpdir=\"" + myTemp + "\" -jar " + pathToVideoEncoderJar +
                " \"" + sourceFile + "\" \"" + destDir + "\" \"" + prefixHere + "\""));
    }


    private File srcDir, targetDir, tempDir;
    private String pathPrefix, pathToVideoEncoderJar;
    private SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    private long pollIntervall = 60;
    private List<String> escapes;
    private boolean debug = false;



    private EncodeDaemon() throws IOException {
        // find config file
        Properties props = new Properties();
        props.load(new FileInputStream(findConfigFile()));

        // config
        this.srcDir = new File(props.getProperty("sourceDir"));
        this.targetDir = new File(props.getProperty("targetDir"));
        this.tempDir = new File(props.getProperty("tempDir"));
        this.pathPrefix = props.getProperty("pathPrefix");
        this.pollIntervall = Long.parseLong(props.getProperty("pollInterval"));
        this.escapes = Arrays.asList(props.getProperty("srcEscapes").split(" "));
        this.debug = Boolean.parseBoolean(props.getProperty("debug"));

        String p = this.getClass().getResource("sample-daemon-config.properties").toExternalForm();
        if(p.contains("!")) p = p.substring(0,p.indexOf('!'));
        if(p.startsWith("jar:")) p = p.substring(4);
        if(p.startsWith("file://")) p = p.substring(7);
        if(p.startsWith("file:")) p = p.substring(5);
        if(!new File(p).isFile())
            throw new IllegalStateException("Cannot find a videoEncoder jar from " + this.getClass().getResource("sample-daemon-config.properties").toExternalForm() + ".");
        this.pathToVideoEncoderJar = p;

    }

    public void run() {
        long lastPolled;
        if(debug) System.err.println("Starting polling...");
        while(true) {
            try {
                lastPolled = System.currentTimeMillis();
                File[] toProcess = findAFileToProcess(srcDir, targetDir);
                if(toProcess!=null) {
                    new Thread() {
                        public void run() {
                            try {
                                startProcess(toProcess[0], toProcess[1].getParentFile());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }.start();
                }

                if(toProcess==null && lastPolled+1000L*pollIntervall > System.currentTimeMillis())
                    Thread.sleep(lastPolled+1000L*pollIntervall -System.currentTimeMillis());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private File[] findAFileToProcess(File src, File dest) throws IOException {
        if(debug) System.err.println("Finding file " + src + " to " + dest + ".");
        String filename = src.getName();
        for(String wildcard: escapes) { if(FilenameUtils.wildcardMatch(filename,wildcard)) {
            if(debug) System.out.println("  Filename is escaped.");
            return null;} }
        File[] result = null;
        for(String fn: src.list()) {
            File file = new File(src, fn), shadowFile = new File(dest, ".shadow" + fn + ".txt");
            if(file.isDirectory()) { // recurse
                File targetDir = new File(dest, fn);
                targetDir.mkdirs();
                result = findAFileToProcess(file, targetDir);
                if(result!=null) return result;
                continue;
            }
            if(debug) System.out.println("Attempting file " + fn);
            if(!Util.isVideoFilename(fn)) {
                if(debug) System.out.println("  Not a video.");
                continue;
            }
            if(!shadowFile.isFile() || file.lastModified() - shadowFile.lastModified() > 1000) {
                if(debug) System.out.println("  Selected.");
                result = new File[] {file, shadowFile};
            } else {
                System.out.println("  Not necessary.");
            }

            // if found... create shadow
            if(result!=null) {
                if(debug) System.out.println("  Creating " + result[1]);
                FileUtils.writeStringToFile(result[1], df.format(new Date()));
                return result;
            }
        }

        if(debug) System.out.println("  Nothing to do.");
        return null;
    }

}
