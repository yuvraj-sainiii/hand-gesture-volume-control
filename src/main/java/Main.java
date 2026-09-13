import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_highgui;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_videoio;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;

import org.bytedeco.javacpp.indexer.IntIndexer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class Main {

    // ==========================================
    // VOLUME RANGE
    // ==========================================

    static final double MIN_DISTANCE = 100.0;
    static final double MAX_DISTANCE = 200.0;

    // ==========================================
    // WINDOWS VOLUME CONTROLLER
    // ==========================================

    static class VolumeController {

        private Process powershell;
        private BufferedWriter writer;
        private Path scriptFile;

        private static final String POWERSHELL_SCRIPT = """
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioEndpointVolume
{
    int f();
    int g();
    int h();
    int i();

    int SetMasterVolumeLevelScalar(
        float fLevel,
        System.Guid pguidEventContext
    );

    int j();

    int GetMasterVolumeLevelScalar(
        out float pfLevel
    );

    int k();
    int l();
    int m();
    int n();

    int SetMute(
        [MarshalAs(UnmanagedType.Bool)] bool bMute,
        System.Guid pguidEventContext
    );

    int GetMute(out bool pbMute);
}

[Guid("D666063F-1587-4E43-81F1-B948E807363F"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice
{
    int Activate(
        ref System.Guid id,
        int clsCtx,
        int activationParams,
        out IAudioEndpointVolume aev
    );
}

[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
 InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator
{
    int f();

    int GetDefaultAudioEndpoint(
        int dataFlow,
        int role,
        out IMMDevice endpoint
    );
}

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
class MMDeviceEnumeratorComObject
{
}

public class Audio
{
    static IAudioEndpointVolume Vol()
    {
        var enumerator =
            new MMDeviceEnumeratorComObject()
            as IMMDeviceEnumerator;

        IMMDevice dev = null;

        Marshal.ThrowExceptionForHR(
            enumerator.GetDefaultAudioEndpoint(
                0,
                1,
                out dev
            )
        );

        IAudioEndpointVolume epv = null;

        var epvid =
            typeof(IAudioEndpointVolume).GUID;

        Marshal.ThrowExceptionForHR(
            dev.Activate(
                ref epvid,
                23,
                0,
                out epv
            )
        );

        return epv;
    }

    public static float Volume
    {
        set
        {
            Marshal.ThrowExceptionForHR(
                Vol().SetMasterVolumeLevelScalar(
                    value,
                    System.Guid.Empty
                )
            );
        }
    }
}
'@

while ($true)
{
    $line = [Console]::ReadLine()

    if ($null -eq $line)
    {
        break
    }

    try
    {
        [Audio]::Volume = [float]$line
    }
    catch
    {
    }
}
""";

        VolumeController() throws Exception {

            scriptFile = Files.createTempFile(
                "gesture-volume-",
                ".ps1"
            );

            Files.writeString(
                scriptFile,
                POWERSHELL_SCRIPT
            );

            powershell = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                scriptFile.toString()
            )
            .redirectErrorStream(true)
            .start();

            writer =
                powershell.outputWriter();
        }

        void setVolume(double volume) {

            try {

                volume =
                    Math.max(
                        0.0,
                        Math.min(1.0, volume)
                    );

                writer.write(
                    String.format(
                        Locale.US,
                        "%.4f%n",
                        volume
                    )
                );

                writer.flush();

            } catch (IOException e) {

                System.out.println(
                    "Volume control error: "
                    + e.getMessage()
                );
            }
        }

        void close() {

            try {

                writer.close();

            } catch (Exception ignored) {
            }

            try {

                powershell.destroy();

            } catch (Exception ignored) {
            }

            try {

                Files.deleteIfExists(
                    scriptFile
                );

            } catch (Exception ignored) {
            }
        }
    }

    // ==========================================
    // MAIN
    // ==========================================

    public static void main(String[] args) {

        VideoCapture camera =
            new VideoCapture(0);

        if (!camera.isOpened()) {

            System.out.println(
                "Camera open nahi hua!"
            );

            return;
        }

        System.out.println(
            "Camera successfully open!"
        );

        // Windows volume controller
        VolumeController volumeController;

        try {

            volumeController =
                new VolumeController();

            System.out.println(
                "Windows volume controller ready!"
            );

        } catch (Exception e) {

            System.out.println(
                "Windows volume controller start nahi hua!"
            );

            camera.release();
            return;
        }

        Mat frame = new Mat();

        // ==========================================
        // SKIN HSV RANGE
        // ==========================================

        Scalar lowerSkin1 =
            new Scalar(0, 10, 30, 0);

        Scalar upperSkin1 =
            new Scalar(25, 255, 255, 0);

        Scalar lowerSkin2 =
            new Scalar(160, 10, 30, 0);

        Scalar upperSkin2 =
            new Scalar(179, 255, 255, 0);

        // ==========================================
        // SMOOTHING
        // ==========================================

        Point smoothIndex = null;
        Point smoothThumb = null;

        double smoothDistance = -1;

        int lastVolume = -1;

        // ==========================================
        // MORPHOLOGY KERNEL
        // ==========================================

        Mat kernel =
            opencv_imgproc.getStructuringElement(
                opencv_imgproc.MORPH_ELLIPSE,
                new Size(5, 5)
            );

        // ==========================================
        // MAIN LOOP
        // ==========================================

        while (true) {

            camera.read(frame);

            if (frame.empty()) {
                break;
            }

            // ==========================================
            // HAND ROI
            // ==========================================

            int roiX = 20;

            int roiY =
                frame.rows() / 5;

            int roiW =
                frame.cols() / 3;

            int roiH =
                (int)
                (frame.rows() * 0.65);

            Rect roiRect =
                new Rect(
                    roiX,
                    roiY,
                    roiW,
                    roiH
                );

            Mat roi =
                new Mat(
                    frame,
                    roiRect
                );

            // ==========================================
            // BGR -> HSV
            // ==========================================

            Mat hsv =
                new Mat();

            opencv_imgproc.cvtColor(
                roi,
                hsv,
                opencv_imgproc.COLOR_BGR2HSV
            );

            // ==========================================
            // HSV LIMIT MATRICES
            // ==========================================

            Mat lowerMat1 =
                new Mat(
                    hsv.rows(),
                    hsv.cols(),
                    opencv_core.CV_8UC3,
                    lowerSkin1
                );

            Mat upperMat1 =
                new Mat(
                    hsv.rows(),
                    hsv.cols(),
                    opencv_core.CV_8UC3,
                    upperSkin1
                );

            Mat lowerMat2 =
                new Mat(
                    hsv.rows(),
                    hsv.cols(),
                    opencv_core.CV_8UC3,
                    lowerSkin2
                );

            Mat upperMat2 =
                new Mat(
                    hsv.rows(),
                    hsv.cols(),
                    opencv_core.CV_8UC3,
                    upperSkin2
                );

            // ==========================================
            // SKIN MASK
            // ==========================================

            Mat mask1 =
                new Mat();

            Mat mask2 =
                new Mat();

            opencv_core.inRange(
                hsv,
                lowerMat1,
                upperMat1,
                mask1
            );

            opencv_core.inRange(
                hsv,
                lowerMat2,
                upperMat2,
                mask2
            );

            Mat mask =
                new Mat();

            opencv_core.bitwise_or(
                mask1,
                mask2,
                mask
            );

            // Noise remove
            opencv_imgproc.morphologyEx(
                mask,
                mask,
                opencv_imgproc.MORPH_OPEN,
                kernel
            );

            opencv_imgproc.morphologyEx(
                mask,
                mask,
                opencv_imgproc.MORPH_CLOSE,
                kernel
            );

            // ==========================================
            // FIND CONTOURS
            // ==========================================

            MatVector contours =
                new MatVector();

            Mat hierarchy =
                new Mat();

            opencv_imgproc.findContours(
                mask,
                contours,
                hierarchy,
                opencv_imgproc.RETR_EXTERNAL,
                opencv_imgproc.CHAIN_APPROX_SIMPLE
            );

            // ==========================================
            // BIGGEST CONTOUR
            // ==========================================

            int biggestContour = -1;

            double biggestArea = 0;

            for (
                int i = 0;
                i < contours.size();
                i++
            ) {

                double area =
                    opencv_imgproc.contourArea(
                        contours.get(i)
                    );

                if (area > biggestArea) {

                    biggestArea =
                        area;

                    biggestContour =
                        i;
                }
            }

            // ==========================================
            // HAND DETECTION
            // ==========================================

            boolean handDetected =
                false;

            Point indexTip = null;
            Point thumbTip = null;

            if (biggestContour != -1) {

                Mat contour =
                    contours.get(
                        biggestContour
                    );

                Rect handRect =
                    opencv_imgproc.boundingRect(
                        contour
                    );

                double rectArea =
                    handRect.width()
                    * handRect.height();

                double aspectRatio =
                    (double)
                    handRect.height()
                    /
                    Math.max(
                        1,
                        handRect.width()
                    );

                double fillRatio =
                    biggestArea
                    /
                    Math.max(
                        1,
                        rectArea
                    );

                double roiArea =
                    roiW * roiH;

                double contourRatio =
                    biggestArea
                    /
                    roiArea;

                double centerY =
                    handRect.y()
                    +
                    handRect.height() / 2.0;

                // --------------------------------------
                // IMPORTANT:
                // Background ko hand na samjhe
                // --------------------------------------

                handDetected =
                    aspectRatio > 0.90
                    &&
                    fillRatio > 0.25
                    &&
                    contourRatio > 0.08
                    &&
                    centerY > roiH * 0.30;

                if (handDetected) {

                    // ==================================
                    // GREEN HAND CONTOUR
                    // ==================================

                    opencv_imgproc.drawContours(
                        roi,
                        contours,
                        biggestContour,
                        new Scalar(
                            0, 255, 0, 0
                        ),
                        2,
                        opencv_imgproc.LINE_8,
                        hierarchy,
                        0,
                        new Point(0, 0)
                    );

                    // ==================================
                    // CONVEX HULL
                    // ==================================

                    Mat hull =
                        new Mat();

                    opencv_imgproc.convexHull(
                        contour,
                        hull,
                        false,
                        true
                    );

                    IntIndexer hullIndexer =
                        hull.createIndexer();

                    int hullCount =
                        hull.rows();

                    // ==================================
                    // FIND THUMB
                    // ==================================

                    double bestThumbX =
                        -Double.MAX_VALUE;

                    for (
                        int i = 0;
                        i < hullCount;
                        i++
                    ) {

                        int x =
                            hullIndexer.get(
                                i,
                                0,
                                0
                            );

                        int y =
                            hullIndexer.get(
                                i,
                                0,
                                1
                            );

                        boolean correctY =
                            y >
                            handRect.y()
                            +
                            handRect.height()
                            * 0.20
                            &&
                            y <
                            handRect.y()
                            +
                            handRect.height()
                            * 0.85;

                        if (correctY) {

                            if (
                                x >
                                bestThumbX
                            ) {

                                bestThumbX =
                                    x;

                                thumbTip =
                                    new Point(
                                        x,
                                        y
                                    );
                            }
                        }
                    }

                    // ==================================
                    // FIND INDEX
                    // ==================================

                    double bestIndexY =
                        Double.MAX_VALUE;

                    double handCenterX =
                        handRect.x()
                        +
                        handRect.width()
                        / 2.0;

                    double handCenterY =
                        handRect.y()
                        +
                        handRect.height()
                        / 2.0;

                    for (
                        int i = 0;
                        i < hullCount;
                        i++
                    ) {

                        int x =
                            hullIndexer.get(
                                i,
                                0,
                                0
                            );

                        int y =
                            hullIndexer.get(
                                i,
                                0,
                                1
                            );

                        boolean correctX =
                            x >
                            handRect.x()
                            +
                            handRect.width()
                            * 0.20
                            &&
                            x <
                            handRect.x()
                            +
                            handRect.width()
                            * 0.75;

                        boolean correctY =
                            y <
                            handRect.y()
                            +
                            handRect.height()
                            * 0.60;

                        if (
                            !correctX
                            ||
                            !correctY
                        ) {
                            continue;
                        }

                        double centerDistance =
                            Math.sqrt(
                                Math.pow(
                                    x
                                    -
                                    handCenterX,
                                    2
                                )
                                +
                                Math.pow(
                                    y
                                    -
                                    handCenterY,
                                    2
                                )
                            );

                        if (
                            centerDistance
                            <
                            handRect.height()
                            * 0.25
                        ) {
                            continue;
                        }

                        if (
                            y <
                            bestIndexY
                        ) {

                            bestIndexY =
                                y;

                            indexTip =
                                new Point(
                                    x,
                                    y
                                );
                        }
                    }

                    // ==================================
                    // SMOOTH FINGERTIPS
                    // ==================================

                    if (
                        indexTip != null
                        &&
                        thumbTip != null
                    ) {

                        double alpha =
                            0.35;

                        if (
                            smoothIndex == null
                            ||
                            smoothThumb == null
                        ) {

                            smoothIndex =
                                new Point(
                                    indexTip.x(),
                                    indexTip.y()
                                );

                            smoothThumb =
                                new Point(
                                    thumbTip.x(),
                                    thumbTip.y()
                                );

                        } else {

                            smoothIndex =
                                new Point(
                                    (int)
                                    (
                                        alpha
                                        * indexTip.x()
                                        +
                                        (1 - alpha)
                                        * smoothIndex.x()
                                    ),
                                    (int)
                                    (
                                        alpha
                                        * indexTip.y()
                                        +
                                        (1 - alpha)
                                        * smoothIndex.y()
                                    )
                                );

                            smoothThumb =
                                new Point(
                                    (int)
                                    (
                                        alpha
                                        * thumbTip.x()
                                        +
                                        (1 - alpha)
                                        * smoothThumb.x()
                                    ),
                                    (int)
                                    (
                                        alpha
                                        * thumbTip.y()
                                        +
                                        (1 - alpha)
                                        * smoothThumb.y()
                                    )
                                );
                        }

                        indexTip =
                            smoothIndex;

                        thumbTip =
                            smoothThumb;

                        // ==================================
                        // DRAW INDEX
                        // ==================================

                        opencv_imgproc.circle(
                            roi,
                            indexTip,
                            8,
                            new Scalar(
                                255, 0, 0, 0
                            ),
                            -1,
                            opencv_imgproc.LINE_8,
                            0
                        );

                        // ==================================
                        // DRAW THUMB
                        // ==================================

                        opencv_imgproc.circle(
                            roi,
                            thumbTip,
                            8,
                            new Scalar(
                                0, 0, 255, 0
                            ),
                            -1,
                            opencv_imgproc.LINE_8,
                            0
                        );

                        // ==================================
                        // LINE
                        // ==================================

                        opencv_imgproc.line(
                            roi,
                            thumbTip,
                            indexTip,
                            new Scalar(
                                255, 255, 0, 0
                            ),
                            3,
                            opencv_imgproc.LINE_8,
                            0
                        );

                        // ==================================
                        // DISTANCE
                        // ==================================

                        double dx =
                            thumbTip.x()
                            -
                            indexTip.x();

                        double dy =
                            thumbTip.y()
                            -
                            indexTip.y();

                        double distance =
                            Math.sqrt(
                                dx * dx
                                +
                                dy * dy
                            );

                        // Distance smoothing
                        if (
                            smoothDistance < 0
                        ) {

                            smoothDistance =
                                distance;

                        } else {

                            smoothDistance =
                                0.25
                                * distance
                                +
                                0.75
                                * smoothDistance;
                        }

                        // ==================================
                        // MAP DISTANCE -> VOLUME
                        // ==================================

                        double volume =
                            (
                                smoothDistance
                                -
                                MIN_DISTANCE
                            )
                            /
                            (
                                MAX_DISTANCE
                                -
                                MIN_DISTANCE
                            );

                        // 0 to 1 lock
                        volume =
                            Math.max(
                                0.0,
                                Math.min(
                                    1.0,
                                    volume
                                )
                            );

                        int volumePercent =
                            (int)
                            Math.round(
                                volume * 100
                            );

                        // ==================================
                        // SET WINDOWS VOLUME
                        // ==================================

                        if (
                            Math.abs(
                                volumePercent
                                -
                                lastVolume
                            ) >= 2
                        ) {

                            volumeController.setVolume(
                                volume
                            );

                            lastVolume =
                                volumePercent;
                        }

                        // ==================================
                        // SHOW DISTANCE
                        // ==================================

                        opencv_imgproc.putText(
                            frame,
                            "Distance: "
                            +
                            String.format(
                                Locale.US,
                                "%.0f",
                                smoothDistance
                            ),
                            new Point(
                                20,
                                80
                            ),
                            opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                            0.8,
                            new Scalar(
                                255, 255, 0, 0
                            ),
                            2,
                            opencv_imgproc.LINE_8,
                            false
                        );

                        // ==================================
                        // SHOW VOLUME
                        // ==================================

                        opencv_imgproc.putText(
                            frame,
                            "Volume: "
                            +
                            volumePercent
                            +
                            "%",
                            new Point(
                                20,
                                120
                            ),
                            opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                            0.8,
                            new Scalar(
                                0, 255, 255, 0
                            ),
                            2,
                            opencv_imgproc.LINE_8,
                            false
                        );

                        // ==================================
                        // INDEX LABEL
                        // ==================================

                        opencv_imgproc.putText(
                            frame,
                            "INDEX",
                            new Point(
                                roiX
                                +
                                indexTip.x(),
                                roiY
                                +
                                indexTip.y()
                                -
                                10
                            ),
                            opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                            0.5,
                            new Scalar(
                                255, 0, 0, 0
                            ),
                            2,
                            opencv_imgproc.LINE_8,
                            false
                        );

                        // ==================================
                        // THUMB LABEL
                        // ==================================

                        opencv_imgproc.putText(
                            frame,
                            "THUMB",
                            new Point(
                                roiX
                                +
                                thumbTip.x(),
                                roiY
                                +
                                thumbTip.y()
                                -
                                10
                            ),
                            opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                            0.5,
                            new Scalar(
                                0, 0, 255, 0
                            ),
                            2,
                            opencv_imgproc.LINE_8,
                            false
                        );

                    } else {

                        opencv_imgproc.putText(
                            frame,
                            "Finding Fingers...",
                            new Point(
                                20,
                                80
                            ),
                            opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                            0.7,
                            new Scalar(
                                0, 255, 255, 0
                            ),
                            2,
                            opencv_imgproc.LINE_8,
                            false
                        );
                    }

                    // ==================================
                    // HAND DETECTED
                    // ==================================

                    opencv_imgproc.putText(
                        frame,
                        "HAND DETECTED",
                        new Point(
                            20,
                            40
                        ),
                        opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                        1.0,
                        new Scalar(
                            0, 255, 0, 0
                        ),
                        2,
                        opencv_imgproc.LINE_8,
                        false
                    );

                } else {

                    // ==================================
                    // NO HAND
                    // ==================================

                    smoothIndex = null;
                    smoothThumb = null;
                    smoothDistance = -1;

                    opencv_imgproc.putText(
                        frame,
                        "Put Hand Inside Box",
                        new Point(
                            20,
                            40
                        ),
                        opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                        0.8,
                        new Scalar(
                            0, 0, 255, 0
                        ),
                        2,
                        opencv_imgproc.LINE_8,
                        false
                    );

                    opencv_imgproc.putText(
                        frame,
                        "Volume: --",
                        new Point(
                            20,
                            80
                        ),
                        opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                        0.8,
                        new Scalar(
                            0, 0, 255, 0
                        ),
                        2,
                        opencv_imgproc.LINE_8,
                        false
                    );
                }

            } else {

                // ==========================================
                // NO CONTOUR
                // ==========================================

                smoothIndex = null;
                smoothThumb = null;
                smoothDistance = -1;

                opencv_imgproc.putText(
                    frame,
                    "Put Hand Inside Box",
                    new Point(
                        20,
                        40
                    ),
                    opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                    0.8,
                    new Scalar(
                        0, 0, 255, 0
                    ),
                    2,
                    opencv_imgproc.LINE_8,
                    false
                );

                opencv_imgproc.putText(
                    frame,
                    "Volume: --",
                    new Point(
                        20,
                        80
                    ),
                    opencv_imgproc.FONT_HERSHEY_SIMPLEX,
                    0.8,
                    new Scalar(
                        0, 0, 255, 0
                    ),
                    2,
                    opencv_imgproc.LINE_8,
                    false
                );
            }

            // ==========================================
            // BLUE ROI BOX
            // ==========================================

            opencv_imgproc.rectangle(
                frame,
                new Point(
                    roiX,
                    roiY
                ),
                new Point(
                    roiX + roiW,
                    roiY + roiH
                ),
                new Scalar(
                    255, 0, 0, 0
                ),
                2,
                opencv_imgproc.LINE_8,
                0
            );

            // ==========================================
            // CAMERA WINDOW
            // ==========================================

            opencv_highgui.imshow(
                "Hand Gesture Volume Control",
                frame
            );

            int key =
                opencv_highgui.waitKey(30);

            if (key == 27) {
                break;
            }
        }

        // ==========================================
        // CLOSE
        // ==========================================

        camera.release();

        volumeController.close();

        opencv_highgui.destroyAllWindows();
    }
}