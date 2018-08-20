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

public class GenericEncodeDaemon {
    public static void main(String[] args) throws Exception {
        boolean helpNeeded = false;

        if(args.length==1 && "-sampleConfig".equals(args[0])) {
            IOUtils.copy(EncodeDaemon.class.getResourceAsStream("sample-genericdaemon-config.properties"), System.out);
            return;
        }

        for(String arg: args) if("--help".equals(arg.toLowerCase())) helpNeeded = true;
        if(findConfigFile()==null) helpNeeded = true;
        if(helpNeeded) {
            System.err.println("Usage java -classpath videoEncoder.jar net.hoplahup.polx.videoencoder.EncodeDaemon \n" +
                    "  After having put the configuration genericencode-daemon.properties in the current directory or in /etc/.\n" +
                    "  Please invoke: \n" +
                    "    java -classpath videoEncoder.jar net.hoplahup.polx.videoencoder.GenericEncodeDaemon -sampleConfig \n" +
                    "  to obtain a suggested configuration. \n");
        } else {
            GenericEncodeDaemon e = new GenericEncodeDaemon();
            e.run();
        }
    }

    private static File findConfigFile() {
        File f = new File("/etc/genericencode-daemon.properties");
        if(f.isFile()) return f;
        f = new File("genericencode-daemon.properties");
        if(f.isFile()) return f;
        return null;
    }

    private void startProcess(File sourceFile, File destFile, File shadowFile) throws Exception {
        System.out.println("Starting process " + sourceFile + " to " + destFile);
        File myTemp = new File(tempDir, df.format(new Date()) + FilenameUtils.getBaseName(sourceFile.getName()).replaceAll(" ", "_"));
        myTemp.mkdirs();
        DefaultExecutor executor = new DefaultExecutor();
        String baseName = FilenameUtils.getBaseName(sourceFile.getName());

        PrintStream debugStream = new PrintStream(new FileOutputStream(new File(myTemp, "debug.log")));
        executor.setStreamHandler(new PumpStreamHandler(debugStream, debugStream));
        // doesn't work
        executor.setWorkingDirectory(myTemp);
        // hence tmp dir first param
        executor.execute(Util.cli(encoderCommandStart +
                " \"" +  myTemp + "\"" + " \"" + sourceFile + "\" \"" + destFile + "\""));
        // cleanup (only if successful)
        for(File file: myTemp.listFiles()) file.delete();
        myTemp.delete();
        shadowFile.delete();
    }


    private File srcDir, targetDir, tempDir;
    private String targetExtension;
    private String[] srcExtensions;
    private String encoderCommandStart;
    private SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
    private long pollIntervall = 60;
    private List<String> escapes;
    private boolean debug = false;



    private GenericEncodeDaemon() throws IOException {
        // find config file
        Properties props = new Properties();
        props.load(new FileInputStream(findConfigFile()));

        // config
        this.srcDir = new File(props.getProperty("sourceDir"));
        this.targetDir = new File(props.getProperty("targetDir"));
        this.tempDir = new File(props.getProperty("tempDir"));
        this.targetExtension = props.getProperty("targetExtension");
        this.srcExtensions = props.getProperty("srcExtensions").split(" ");
        this.pollIntervall = Long.parseLong(props.getProperty("pollInterval"));
        this.escapes = Arrays.asList(props.getProperty("srcEscapes").split(" "));
        this.debug = Boolean.parseBoolean(props.getProperty("debug"));
        this.encoderCommandStart = props.getProperty("encoderCommandStart");

    }

    public void run() {
        long lastPolled;
        if(debug) System.err.println("Starting polling...");
        while(true) {
            try {
                lastPolled = System.currentTimeMillis();
                File[] toProcess = findAFileToProcess(srcDir, targetDir);
                if(toProcess==null)
                    if(debug) System.out.println("  Nothing to do.");
                if(toProcess!=null) {
                    new Thread() {
                        public void run() {
                            try {
                                startProcess(toProcess[0], toProcess[1], toProcess[2]);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }.start();
                }

                if(toProcess==null && lastPolled+300L*pollIntervall > System.currentTimeMillis())
                    Thread.sleep(lastPolled+300L*pollIntervall -System.currentTimeMillis());
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
            File file = new File(src, fn);
            File targetFile = new File(dest, FilenameUtils.getBaseName(fn)+"." + targetExtension),
                shadowFile = new File(dest, ".shadow_" + FilenameUtils.getBaseName(fn)+"." + targetExtension);
            if(file.isDirectory()) { // recurse
                File targetDir = new File(dest, fn);
                targetDir.mkdirs();
                result = findAFileToProcess(file, targetDir);
                if(result!=null) return result;
                continue;
            }
            if(debug) System.out.println("Attempting file " + fn);
            if(!isCandidateFilename(fn)) {
                if(debug) System.out.println("  Not a candidate.");
                continue;
            }
            System.err.println("is file? " + shadowFile.isFile());
            if(!targetFile.isFile() || file.lastModified() - targetFile.lastModified() > 1000) {
                if(!(shadowFile.isFile())) {
                    if (debug) System.out.println("  Selected.");
                    result = new File[]{file, targetFile, shadowFile};
                }
            } else {
                System.out.println("  Not necessary.");
            }

            // if found... create shadow
            if(result!=null) {
                FileUtils.writeStringToFile(shadowFile, df.format(new Date()));
                return result;
            }
        }
        return null;
    }

    private boolean isCandidateFilename(String fn) {
        for(String ext: srcExtensions) {
            if(fn.toLowerCase().endsWith("." + ext.toLowerCase())) return true;
        }
        return false;
    }
}
