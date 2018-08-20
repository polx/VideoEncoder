package net.hoplahup.polx.videoencoder;

import org.apache.commons.exec.CommandLine;

import java.util.Arrays;
import java.util.List;

public class Util {

    static CommandLine cli(String line) {
        System.out.println("Executing: " + line);
        return CommandLine.parse(line);
    }

    static List videoExtensions = Arrays.asList("mp4","m4v","ogv","webm","mov","mpeg","avi");
    static boolean isVideoFileName(String fn) {
        fn = fn.toLowerCase();
        if(fn.contains(".")) fn = fn.substring(fn.lastIndexOf('.')+1);
        return videoExtensions.contains(fn);
    }

}
