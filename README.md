# Hand Gesture Volume Control 🎚️✋

A real-time hand gesture based volume control system built using **Java and OpenCV**.

The application uses the distance between the **thumb and index finger** to control the Windows system volume.

## ✨ Features

- 📷 Real-time webcam hand detection
- ✋ Thumb and index fingertip detection
- 📏 Distance-based volume calculation
- 🔊 Controls actual Windows system volume
- 🎯 Volume mapped from 0% to 100%
- 🖥️ Built with Java and OpenCV
- ⚡ Real-time processing with smooth volume changes

## 🛠️ Technologies Used

- Java 17
- OpenCV
- JavaCPP / Bytedeco OpenCV
- Maven
- Windows PowerShell

## ⚙️ How It Works

1. The webcam captures the video.
2. OpenCV detects the hand using skin-color segmentation.
3. The thumb and index finger are detected.
4. The distance between the two fingertips is calculated.
5. The distance is mapped to a volume percentage.
6. The Windows master volume is updated in real time.

### Gesture Mapping

| Finger Distance | Volume |
|---|---:|
| ~100 px | 0% |
| ~150 px | 50% |
| ~200 px | 100% |

## 🚀 How to Run

### Prerequisites

- Java 17 or higher
- Maven
- Webcam
- Windows OS

### Run the Project

Clone the repository:

```bash
git clone https://github.com/yuvraj-sainiii/hand-gesture-volume-control.git
```
## 🎥 Demo

The project detects the hand in real time using the webcam and controls the Windows system volume based on the distance between the thumb and index finger.

[▶️ Watch the Hand Gesture Volume Control Demo](hand_gesture_volume_demo.mp4)

### Volume Control

- 🤏 Fingers close → Volume decreases
- 🤌 Fingers apart → Volume increases
- ✋ No hand detected → Volume control stops

