# VideoEncoder

A java tool and daemon to encode videos in web-ready forms using ffmpeg.

The video encoder takes any video supported by ffmpeg and converts it to a
small ("low-Q") version, with maximum width of 640 pixels as well to a version with maximum width 1200 pixels.
Moreover, the encoder generates a video element and a javascript page including the information. 

## Usage over the command line

See [the help page](src/main/java/resources/net/hoplahup/polx/videoencoder/help-page.txt).

See [the sample daemon config](src/main/java/resources/net/hoplahup/polx/videoencoder/sample-daemon-config.properties) to see how to configure the encoder-daemon.

## Installation

First install [ffmpeg](https://ffmpeg.org).

Download the source code and compile with [maven](https://maven.apache.org).

`git clone https://github.com/polx/VideoEncoder.git`
`mvn package`

You obtaine a `videoEncoder.jar` in the `target` directory which is the main java file.

You can then invoke the video-encoder using the help page.

Or invoke the encode daemon using:
`java -classpath target/videoEncoder.jar net.hoplahup.polx.videoencoder.EncodeDaemon`


## License

Under [Apache License 2.0](LICENSE).
Feel free to request more permissive licenses.

