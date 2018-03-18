package net.hoplahup.polx.videoencoder;

import org.apache.commons.exec.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;

import java.io.File;
import java.io.IOException;
import java.lang.System;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EncodeVideo {

    public static void main(String[] args) throws Exception {
        boolean needHelp = false;
        for(String a: args) needHelp = needHelp
                || a.toLowerCase().startsWith("--help") || a.toLowerCase().startsWith("-h");
        needHelp = needHelp || args.length!=2 && args.length!=3 && args.length!=1;
        if(needHelp) {
            IOUtils.copy(EncodeVideo.class.getResourceAsStream("help-page.txt"), System.err);
            System.exit(1);
        }
        String outputDir = null;
        if(args.length>1)
            outputDir = args[1];
        else {
            outputDir = new File(args[0], FilenameUtils.getBaseName(args[0])).getPath();
        }

        EncodeVideo enc = new EncodeVideo(args[0], outputDir);
        if(args.length>2)
            enc.baseOutputReferencePath = args[2];
        if(System.getProperty("pathPrefix")!=null)
            enc.baseOutputReferencePath = System.getProperty("pathPrefix");
                    enc.run();
        if(enc.status==Status.FINISHED)
            System.exit(0);
        else
            System.exit(1);

    }

    public EncodeVideo(String pathToVideo, String outputDirectoryPath) throws Exception {
        if(pathToVideo.startsWith("\"")) pathToVideo = pathToVideo.substring(1);
        if(pathToVideo.endsWith("\"")) pathToVideo = pathToVideo.substring(0, pathToVideo.length()-1);
        this.sourceFile = new File(pathToVideo);
        this.targetDir = new File(outputDirectoryPath);
        this.baseName = sourceFile.getName();
        this.statusFile = new File(outputDirectoryPath, baseName +".status");
        if(baseName.startsWith("orig_")) {
            baseName = baseName.substring(5);
            prefix = "orig_";
        }

        suffix = baseName;
        int p = suffix.lastIndexOf('.');
        if(p>0 && p+1<baseName.length()) {
            suffix = baseName.substring(p+1);
            baseName = baseName.substring(0, p);
        }
        if(System.getProperty("encoder.tmpdir")==null)
            this.workingDirectory = new File(System.getProperty("user.dir"));
        else
            this.workingDirectory = new File(System.getProperty("encoder.tmpdir"));
        workingDirectory.mkdirs();
    }

    private File sourceFile, targetDir, statusFile, workingDirectory;
    private DefaultExecutor executor;
    private int originalWidth =-1, originalHeight =-1;
    private int hqWidth=-1, hqHeight=-1, lqWidth=-1, lqHeight=-1;
    private String ffmpeg = System.getProperty("ffmpeg.path","ffmpeg"),
        processCommand = System.getProperty("processEncodingResult");
    private String prefix, baseName, suffix, originalFileName = null;
    private Status status = Status.STARTING;
    private String baseOutputReferencePath = "";

    private enum Status { STARTING, PROCESSING_LOW, PROCESSING_HIGH, FINISHED, BROKEN }


    public void run() {
        try {
            status = Status.STARTING;
            executor = new DefaultExecutor();
            executor.setStreamHandler(new PumpStreamHandler(System.out, System.err));
            outputSizes();

            // FileUtils.writeStringToFile(statusFile, "encoding");

            executor.getStreamHandler().stop();
            readWidthAndHeight();
            computeNewWidthAndHeight();

            executor.setStreamHandler(new PumpStreamHandler(System.out, System.err));

            status = Status.PROCESSING_LOW;
            encodePosters();
            outputSizes();
            encodeLQ();

            status = Status.PROCESSING_HIGH;
            outputSizes();
            encodeHQ();
            copyOriginal();

            status = Status.FINISHED;
            outputSizes();


        } catch (Exception e) {
            e.printStackTrace();
            status = Status.BROKEN;
            try {
                outputSizes();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        } finally {
            System.err.println("Finished (status: " + status + ").");
        }
    }


    private void readWidthAndHeight() throws Exception {
        int[] results = readWidthAndHeightS(sourceFile, executor, ffmpeg);
        originalWidth = results[0];
        originalHeight = results[1];
    }

    protected static int[] readWidthAndHeightS(File sourceFile, Executor executor, String pathToFFMPEG) throws Exception {

        ByteArrayOutputStream buff = new ByteArrayOutputStream();
        ExecuteStreamHandler oldStreamHandler = executor.getStreamHandler();

        executor.setStreamHandler(new PumpStreamHandler(buff));
        executor.setExitValue(1);
        executor.execute(c(pathToFFMPEG + " -i " + sourceFile.getPath()));

        executor.getStreamHandler().stop();


        int[] widthHeight = new int[2];
        String[] lines = buff.toString("utf-8").split("\\n|\\r");
        for(String line: lines) {
            line = line.trim();
            if(line.startsWith("Stream #0") && line.contains("Video:")) {
                System.out.println("Good line: " + line);
                Matcher m = Pattern.compile(", ([0-9]+)x([0-9]+),? ").matcher(line);
                if(m.find()) {
                    widthHeight[0] = Integer.parseInt(m.group(1));
                    widthHeight[1] = Integer.parseInt(m.group(2));
                }
            }
        }
        System.out.println("identification result: width: " + widthHeight[0]+
                " height: " + widthHeight[1]);

        executor.setStreamHandler(oldStreamHandler);
        executor.setExitValue(0);
        return widthHeight;
    }

    private void computeNewWidthAndHeight() {
        // based on http://www.longtailvideo.com/support/jw-player/28838/mp4-video-encoding
        // -- we aim to create two sizes: one that fits inside 640 x 360
        //    and one that fits inside 1280 x 720

        // define values for low-quality lq
        if(originalWidth<= 640 && originalHeight <= 360) {
            // do not change resolutions for any of the targets!
            lqWidth = originalWidth;
            lqHeight = originalHeight;
        } else {
            float lqRatioY = 360*1.0f/originalHeight, lqRatioX = 640*1.0f/originalWidth;
            if(lqRatioX > lqRatioY) {
                lqHeight = (int) (lqRatioY*originalHeight);
                lqWidth  = (int) (lqRatioY*originalWidth);
            } else {
                lqHeight = (int) (lqRatioX*originalHeight);
                lqWidth  = (int) (lqRatioX*originalWidth);
            }
        }

        // define values for low-quality hq
        if(originalWidth<= 1280 && originalHeight <= 720) {
            // do not change resolutions for any of the targets!
            hqWidth = originalWidth;
            hqHeight = originalHeight;
        } else {
            float hqRatioY = 720*1.0f/originalHeight, hqRatioX = 1280*1.0f/originalWidth;
            if(hqRatioX > hqRatioY) {
                hqHeight = (int) hqRatioY*originalHeight;
                hqWidth  = (int) hqRatioY*originalWidth;
            } else {
                hqHeight = (int) hqRatioX*originalHeight;
                hqWidth  = (int) hqRatioX*originalWidth;
            }
        }

        lqWidth = makeEven(lqWidth);
        lqHeight = makeEven(lqHeight);
        hqWidth = makeEven(hqWidth);
        hqHeight = makeEven(hqHeight);

        System.out.println("Target res: lq: " + lqWidth + "x" + lqHeight + " and hq:" + hqWidth + "x" + hqHeight);
    }

    private int makeEven(int i) {
        if(i%2==1) return i+1;
        return i;
    }


    private void encodePosters() throws Exception {

        System.out.println(" ===================== jpeg: extract poster (lq) ===========================================");
        FileUtils.deleteQuietly(new File(workingDirectory, baseName + "-lq.jpeg"));
        executor.execute(c(
                ffmpeg + " -i \"" + sourceFile.getPath() + "\"  -ss 00:00:01.00 -vcodec mjpeg -vf scale=" + lqWidth + ":" + lqHeight + " -vframes 1 -f image2  \"" + new File(workingDirectory, baseName + "-lq.jpg\"")
        ));

        FileUtils.deleteQuietly(new File(targetDir, baseName + "-lq.jpg"));
        FileUtils.moveFile(new File(workingDirectory, baseName  + "-lq.jpg"),
                new File(targetDir, baseName  + "-lq.jpg"));

        System.out.println(" ===================== jpeg: extract poster (hq) ===========================================");
        FileUtils.deleteQuietly(new File(workingDirectory, baseName + "-hq.jpeg"));
        executor.execute(c(
                ffmpeg + " -i \"" + sourceFile.getPath() + "\"  -ss 00:00:01.00 -vcodec mjpeg -vf scale=" + hqWidth + ":" + hqHeight + " -vframes 1 -f image2  \"" + new File(workingDirectory, baseName + "-hq.jpg\"")
        ));
        FileUtils.deleteQuietly(new File(targetDir, baseName + "-hq.jpg"));
        FileUtils.moveFile(new File(workingDirectory, baseName  + "-hq.jpg"),
                new File(targetDir, baseName  + "-hq.jpg"));
    }

    private void encodeHQ() throws Exception {
        executor.setWorkingDirectory(workingDirectory);
        System.out.println("Working directory: " + executor.getWorkingDirectory());

        // mp4: two pass
        File firstPassFile = null;
        try {
            System.out.println(" ===================== MP4: HQ: first pass ===========================================");
            FileUtils.deleteQuietly(new File(workingDirectory, baseName  + "-hq.mp4"));
            firstPassFile = new File(workingDirectory, baseName + "-hq.firstpass.mp4");
            executor.execute(c(
                    ffmpeg + " -i \"" + sourceFile.getPath() + "\" -y -vcodec libx264 -vprofile high -preset slow -b:v 1000k -maxrate 1500k -bufsize 2000k " +
                            "-vf scale=" + hqWidth + ":" + hqHeight + " -threads 0 -c:a aac -b:a 128k -pass 1 -an -f mp4  -strict -2 \"" + firstPassFile + "\""
            ));
            System.out.println(" ===================== MP4: HQ: second pass ===========================================");
            executor.execute(c(
                    ffmpeg + " -i \"" + sourceFile.getPath() + "\" -y -vcodec libx264 -vprofile high -preset slow -b:v 1000k -maxrate 1500k -bufsize 2000k " +
                            "-vf scale=" + hqWidth + ":" + hqHeight + " -threads 0 -c:a aac -b:a 128k -pass 2 -f mp4  -strict -2 \"" + new File(workingDirectory, baseName + "-hq.mp4\"")
            ));
            FileUtils.deleteQuietly(new File(targetDir, baseName  + "-hq.mp4"));
            FileUtils.moveFile(new File(workingDirectory, baseName  + "-hq.mp4"),
                    new File(targetDir, baseName  + "-hq.mp4"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        firstPassFile.delete();

        // webm: two pass
        try {
            System.out.println(" ===================== WEBM: HQ: first pass ===========================================");
            firstPassFile = new File(workingDirectory, baseName  + "-hq.firstpass.webm");
            FileUtils.deleteQuietly(new File(workingDirectory, baseName  + "-hq.webm"));
            executor.execute(c(
                    ffmpeg + " -i \"" + sourceFile.getPath() + "\" -y -codec:v libvpx -quality good -cpu-used 0 -b:v 1000k -qmin 10 -qmax 42 -maxrate 1500k -bufsize 2000k -threads 4 " +
                            "-vf scale=" + hqWidth + ":" + hqHeight + " -codec:a libvorbis -b:a 128k -pass 1 -f webm \"" + firstPassFile + "\""
            ));
            System.out.println(" ===================== WEBM: HQ: second pass ===========================================");
            executor.execute(c(
                    ffmpeg + " -i \"" + sourceFile.getPath() + "\" -y -codec:v libvpx -quality good -cpu-used 0 -b:v 1000k -qmin 10 -qmax 42 -maxrate 1500k -bufsize 2000k -threads 4 " +
                            "-vf scale=" + hqWidth + ":" + hqHeight + " -codec:a libvorbis -b:a 128k -pass 2 -f webm \"" + new File(workingDirectory, baseName  + "-hq.webm\"")
            ));
            FileUtils.deleteQuietly(new File(targetDir, baseName  + "-hq.webm"));
            FileUtils.moveFile(new File(workingDirectory, baseName  + "-hq.webm"),
                    new File(targetDir, baseName  + "-hq.webm"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        firstPassFile.delete();

        new File(workingDirectory, "ffmpeg2pass-0.log").delete();
        new File(workingDirectory, "ffmpeg2pass-0.log.mbtree").delete();


    }


    private void encodeLQ() throws Exception {
        executor.setWorkingDirectory(workingDirectory);
        System.out.println("Working directory: " + executor.getWorkingDirectory());
        File firstPassFile = null;
        try {
            // mp4: two pass
            System.out.println(" ===================== MP4: LQ: first pass ===========================================");
            firstPassFile = new File(workingDirectory, baseName + "-lq.firstpass.mp4");
            FileUtils.deleteQuietly(new File(workingDirectory, baseName  + "-lq.mp4"));
            executor.execute(c(
                    ffmpeg + " -i " + sourceFile.getPath() + " -y -vcodec libx264 -vprofile baseline -preset slow -b:v 200k -maxrate 300k -bufsize 600k " +
                            "-vf scale=" + lqWidth + ":" + lqHeight + " -threads 0 -c:a aac -b:a 64k -pass 1 -an -f mp4  -strict -2" + firstPassFile
            ));
            System.out.println(" ===================== MP4: LQ: second pass ===========================================");
            executor.execute(c(
                    ffmpeg + " -i " + sourceFile.getPath() + " -vcodec libx264 -vprofile baseline -preset slow -b:v 200k -maxrate 300k -bufsize 600k " +
                            "-vf scale=" + lqWidth + ":" + lqHeight + " -threads 0  -c:a aac -b:a 64k -pass 2 -f mp4  -strict -2 " + new File(workingDirectory, baseName + "-lq.mp4")
            ));
            FileUtils.deleteQuietly(new File(targetDir, baseName  + "-lq.mp4"));
            FileUtils.moveFile(new File(workingDirectory, baseName  + "-lq.mp4"),
                    new File(targetDir, baseName  + "-lq.mp4"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        firstPassFile.delete();

        // webm: two pass
        System.out.println(" ===================== WEBM: LQ: first pass ===========================================");
        try {
            firstPassFile = new File(workingDirectory, baseName  + "-lq.firstpass.webm");
            FileUtils.deleteQuietly(new File(workingDirectory, baseName  + "-lq.webm"));
            executor.execute(c(
                    ffmpeg + " -i " + sourceFile.getPath() + " -codec:v libvpx -quality good -cpu-used 0 -b:v 200k -qmin 10 -qmax 42 -maxrate 300k -bufsize 600k -threads 4 " +
                            "-vf scale=" + lqWidth + ":" + lqHeight + " -codec:a libvorbis -b:a 64k -pass 1 -an -f webm " + firstPassFile
            ));
            System.out.println(" ===================== WEBM: LQ: second pass ===========================================");
            executor.execute(c(
                    ffmpeg + " -i " + sourceFile.getPath() + " -codec:v libvpx -quality good -cpu-used 0 -b:v 200k -qmin 10 -qmax 42 -maxrate 300k -bufsize 600k -threads 4 " +
                            "-vf scale=" + lqWidth + ":" + lqHeight + " -codec:a libvorbis -b:a 128k -pass 2 -f webm " + new File(workingDirectory, baseName  + "-lq.webm")
            ));
            FileUtils.deleteQuietly(new File(targetDir, baseName  + "-lq.webm"));
            FileUtils.moveFile(new File(workingDirectory, baseName  + "-lq.webm"),
                    new File(targetDir, baseName  + "-lq.webm"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        firstPassFile.delete();

        new File(workingDirectory, "ffmpeg2pass-0.log").delete();
        new File(workingDirectory, "ffmpeg2pass-0.log.mbtree").delete();

    }

    private void copyOriginal() throws Exception {
        originalFileName = "orig_" + baseName  + "." + suffix;
        FileUtils.copyFile(sourceFile,
                new File(targetDir, originalFileName));
    }


    private void outputSizes() throws IOException  {
        String statusMessage = null;
        String htmlElement = null;
        switch(status) {
            case STARTING:
                statusMessage = "'video.processingMessages.starting'";
                htmlElement = render("video-element-encoding.html");
                break;
            case PROCESSING_LOW:
                statusMessage = "'video.processingMessages.processingLow'";
                htmlElement = render("video-element-encoding.html");
                break;
            case PROCESSING_HIGH:
                statusMessage = "["     + sequenceMD("lq", baseName + "-lq.webm", baseName+"-lq.jpg", lqWidth, lqHeight, 260) + ", "
                        + sequenceMD("lq", baseName + "-lq.mp4",  baseName+"-lq.jpg", lqWidth, lqHeight, 260) + ", "
                        +"]";
                htmlElement = render("video-element-LQ.html");
                break;
            case BROKEN:
                statusMessage = "'video.processingMessages.error'";
                htmlElement = render("video-element-broken.html");
                break;
            case FINISHED:
                statusMessage = "["     + sequenceMD("lq", baseName + "-lq.webm", baseName+"-lq.jpg", lqWidth, lqHeight, 260) + ", "
                        + sequenceMD("lq", baseName + "-lq.mp4",  baseName+"-lq.jpg", lqWidth, lqHeight, 260) + ", "
                        + sequenceMD("hq", baseName + "-hq.webm", baseName+"-hq.jpg", hqWidth, hqHeight, 1100) + ", "
                        + sequenceMD("hq", baseName + "-hq.mp4",  baseName+"-hq.jpg", hqWidth, hqHeight, 1100)
                        +"]";
                htmlElement = render("video-element-HQ.html");
                break;
        }

        if(statusMessage==null) statusMessage = "'video.processingMessages.error'";
        statusMessage = "window['video_"+ baseName + "_sizes']=" + statusMessage + ";";
        if(originalFileName!=null)
            statusMessage = statusMessage + "\n window['video_"+baseName+"_originalName']=\""+originalFileName+"\";";

        FileUtils.writeStringToFile(new File(workingDirectory, baseName + "-sizes.js"), statusMessage);
        FileUtils.writeStringToFile(new File(targetDir, baseName + "-sizes.js"), statusMessage);
        FileUtils.writeStringToFile(new File(targetDir, "embed.html"), htmlElement);

        // TODO: why is this here?
        //if(processCommand !=null && targetDir!=null && workingDirectory!=null) {
        //    executor.execute(c(processCommand + " " + targetDir.getAbsolutePath() + " " + workingDirectory.getName()));
        //}

    }
    private String sequenceMD(String label, String file, String posterFile, int width, int height, int bandWidth) {
        return "{label:'" + label + "', file:'" + file + "', image: '"+ posterFile + "', width:" + width + ", height: " + height + ", bandwidth: " + bandWidth + "}";
    }


    private String render(String rsrcName) throws IOException  {
        Map<String,String> dict = new HashMap<String,String>();
        dict.put("baseName",    baseOutputReferencePath + baseName);
        dict.put("poster",      baseOutputReferencePath + baseName+"-lq.jpeg");
        dict.put("lqMP4",       baseOutputReferencePath + baseName+"-lq.mp4");
        dict.put("hqMP4",       baseOutputReferencePath + baseName+"-hq.mp4");
        dict.put("hqWebM",      baseOutputReferencePath + baseName+"-hq.webm");
        dict.put("lqWebM",      baseOutputReferencePath + baseName+"-lq.webm");
        dict.put("original",    baseOutputReferencePath + originalFileName);
        String text = IOUtils.toString(this.getClass().getResourceAsStream(rsrcName));


        for(Map.Entry<String,String> entry: dict.entrySet()) {
            Matcher m = Pattern.compile("\\{"+entry.getKey()+"\\}").matcher(text);
            text = m.replaceAll(entry.getValue());
        }
        return text;
        /* works only in java 9
        Matcher m = Pattern.compile("\\{[^}]*\\}").matcher(text);
        return m.replaceAll(match -> dict.get(match.group().substring(1, match.group().length()-1)));
        */
    }



    private static CommandLine c(String line) {
        System.out.println("Executing: " + line);
        return CommandLine.parse(line);
    }



}
