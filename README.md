# Rotation Angle Estimation for Visually Impaired Assistance  
Using Camera and IMU Sensors

_A project combining Android and Python to estimate yaw rotation for assisting blind and visually impaired persons (BVIPs) with spatial orientation._

> **Author:** Isa Sadigli  
> **Institution:** Friedrich-Alexander-Universität Erlangen-Nürnberg (FAU)  
> **Advisor:** Hakan Calim  
> **Project Type:** Master Project (Computer Science)  
> **Report:** [`master_project_IsaSadigli.pdf`](https://github.com/isasadiqli/rotation-angle-estimation/blob/main/master_project_IsaSadigli.pdf)

---

## 📖 Overview

This project investigates and implements two main approaches for **rotation angle estimation**:

1. **Camera-based methods** – Using optical flow, affine transformations, and feature tracking to estimate yaw rotation.
2. **Sensor-based methods** – Using smartphone IMU sensors (gyroscope, magnetometer, accelerometer, GPS) with **sensor fusion** (complementary filter) for robust yaw estimation.

The end goal is to support **visually impaired users** in spatial orientation and navigation, enabling reliable guidance systems that work in real-world conditions.

---

## ✨ Key Contributions

- 📱 Developed an **Android app** to record synchronized video and IMU sensor streams (gyroscope, accelerometer, magnetometer, GPS).
- 🎥 Implemented **camera-based yaw estimation**:
  - Initial frame-to-frame optical flow comparison.
  - Accumulative angle calculation with RANSAC outlier rejection.
  - Segmentation & feature optimization (SIFT + Lucas-Kanade).
- 🧭 Developed a **sensor fusion approach**:
  - Gyroscope integration for responsiveness.
  - Magnetometer for absolute heading correction.
  - Complementary filter to combine both, reducing drift and noise.
- 🧪 Evaluated both approaches under static and dynamic conditions:
  - Controlled rotations (90°, 180°, 360°).
  - Walking with turns and environmental transitions.
  - Indoor vs outdoor, low-light, and magnetic interference scenarios.
- 🔍 Results show that **sensor fusion outperforms vision-based methods** in accuracy, stability, and computational efficiency.

---

## 📂 Repository Structure

```
rotation-angle-estimation/
├─ CameraApp/                  # Android application (Kotlin)
│   ├─ ...                     # Source code for camera + sensor logging
│
├─ methods/                    # Python methods for offline processing
│   ├─ camera_rotation.py       # Camera-based angle estimation (optical flow, SIFT, RANSAC)
│   ├─ imu_rotation.py          # IMU-only yaw estimation
│   ├─ fusion_filter.py         # Complementary filter combining gyro + magnetometer
│   ├─ evaluate.py              # Evaluation scripts for plots/metrics
│   └─ utils.py                 # Helper functions (preprocessing, calibration, plotting)
│
├─ master_project_IsaSadigli.pdf # Full project report
└─ .gitattributes
```

---

## 🛠 Methodology

### 1. Camera-Based Approach
- **Initial Implementation:** Direct comparison with first frame using affine transforms.  
  → Failed for >45° rotations due to feature mismatch.
- **Accumulative Method:** Compared consecutive frames and summed rotations.  
  → Better for slow rotations, but accumulated drift and gait artifacts during walking.
- **Optimizations:**  
  - Processed every 20th frame (≈1.5 Hz effective rate).  
  - Applied morphological filters to clean segmentation.  
  - Added scalar correction to reduce underestimation.

**Limitations:**  
- Sensitive to lighting, texture, and walking-induced sway.  
- Computationally heavy, not suitable for real-time guidance.

---

### 2. Sensor-Based Approach
- **Sensors used:** Gyroscope, Magnetometer, Accelerometer, GPS.  
- **Findings:**  
  - GPS unreliable (gaps, low update rate, poor heading).  
  - Accelerometer limited for yaw.  
  - Gyroscope accurate short-term but drifts.  
  - Magnetometer stable long-term but noisy indoors.  
- **Fusion:** Complementary filter (`alpha = 0.98`) → best of both worlds:
  - Short-term gyro responsiveness.
  - Long-term magnetometer correction.
- **Calibration:** Hard-iron correction for magnetometer offsets.  
- **Evaluation:** Maintained <5° error even in extended rotations, unlike vision-based (~15–20° drift).

---

## 🚀 Getting Started

### Android App
**Requirements:**
- Android Studio (Giraffe+ recommended)
- Android SDK 33+
- Device with camera + IMU sensors

**Setup:**
```bash
cd CameraApp/
# Open in Android Studio and run on device
```

**Output:**
- `imu.csv` – Accelerometer, gyroscope, magnetometer, GPS logs
- `video.mp4` – Camera stream

---

### Python Methods

**Requirements:**
- Python 3.9+
- Install dependencies:
  ```bash
  pip install -r methods/requirements.txt
  ```

**Example usage:**

1. **Camera-based rotation:**
   ```bash
   python methods/camera_rotation.py --video data/video.mp4 --out results/camera.json
   ```

2. **IMU yaw:**
   ```bash
   python methods/imu_rotation.py --imu data/imu.csv --out results/imu.json
   ```

3. **Sensor fusion:**
   ```bash
   python methods/fusion_filter.py --imu data/imu.csv --out results/fused.json
   ```

4. **Evaluation:**
   ```bash
   python methods/evaluate.py --pred results/fused.json --gt data/groundtruth.json --plot
   ```

---

## 📊 Results

- **Camera-based:**
  - Worked for slow, static rotations.
  - Failed during walking due to sway-induced artifacts.
  - Drift errors up to 20° in 360° tests.

- **Sensor fusion:**
  - Accurate, robust, and lightweight.
  - Drift <5° in 360° tests.
  - Stable under different environments (indoor/outdoor, low light).
  - High temporal resolution (up to 100 Hz).

---

## 📄 Project Report

The complete methodology, figures, tables, and references are available in  
👉 [`master_project_IsaSadigli.pdf`](./master_project_IsaSadigli.pdf)

---

## ⚠️ Limitations

- Camera-based methods unsuitable for real-time assistive applications.  
- Magnetometer performance degraded near metal objects/electronics.  
- GPS unreliable for micro-navigation.  

---

## 🔮 Future Work

- Real-time **on-device fusion** integrated directly in Android app.  
- Multi-modal system combining:
  - IMU fusion for rotation,
  - Camera for obstacle detection,
  - GPS for macro-navigation.  
- User testing with visually impaired participants.

---
