import cv2
import numpy as np
from tqdm import tqdm
import math


def correct_drift_v2(cumulative_angle, drift_threshold=5, correction_factor=0.001):
    if abs(cumulative_angle) < drift_threshold:
        return cumulative_angle
    correction = correction_factor * (abs(cumulative_angle) - drift_threshold)

    return cumulative_angle - np.sign(cumulative_angle) * correction


def extract_yaw_from_rotation(R):
    # Tait-Bryan angles: yaw (Y), pitch (X), roll (Z) - ZYX order
    if abs(R[0, 0]) < 1e-6 and abs(R[1, 0]) < 1e-6:
        yaw = 0  # Gimbal lock
    else:
        yaw = np.arctan2(R[2, 0], R[0, 0])
    return np.degrees(yaw)


def track_keypoints_v2(frames, keypoints_descriptors, interval=20, scalar_multiplier=1.5, K=None):
    if K is None:
        # Approximate camera intrinsics if not provided
        h, w = frames[0].shape[:2]
        f = 1.0 * w  # Approximate focal length
        K = np.array([[f, 0, w / 2],
                      [0, f, h / 2],
                      [0, 0, 1]])

    lk_params = dict(winSize=(15, 15),
                     maxLevel=2,
                     criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 10, 0.03))

    angles = []
    total_frames = len(frames)
    cumulative_angle = 0
    reference_image = 0

    for i in tqdm(range(0, total_frames - interval, interval), desc="Processing Frames"):
        old_frame = frames[reference_image]
        new_frame = frames[i + interval]

        old_gray = cv2.cvtColor(old_frame, cv2.COLOR_BGR2GRAY)
        new_gray = cv2.cvtColor(new_frame, cv2.COLOR_BGR2GRAY)

        p0 = np.array([kp.pt for kp in keypoints_descriptors[reference_image][0]], dtype=np.float32).reshape(-1, 1, 2)
        p1, st, err = cv2.calcOpticalFlowPyrLK(old_gray, new_gray, p0, None, **lk_params)

        good_new = p1[st == 1]
        good_old = p0[st == 1]

        if len(good_old) < 8 or len(good_new) < 8:
            print(f"Skipping frame {i} due to insufficient keypoints")
            angles.extend([cumulative_angle] * interval)
            continue
        else:
            try:
                E, mask = cv2.findEssentialMat(good_old, good_new, K, method=cv2.RANSAC, threshold=1.0)
                if E is None or E.shape != (3, 3):
                    print(f"Skipping frame {i} due to invalid essential matrix")
                    angles.extend([cumulative_angle] * interval)
                    continue

                _, R, _, _ = cv2.recoverPose(E, good_old, good_new, K)

                # --- Reject sudden yaw jumps ---
                yaw = extract_yaw_from_rotation(R)

                MAX_YAW_STEP = 4  # degrees — adjust as needed
                if abs(yaw) > MAX_YAW_STEP:
                    print(f"[Frame {i}] Rejected yaw step: {yaw:.2f}° (too large)")
                    yaw = 0  # Discard this step or replace with smoother alternative

                # Accumulate and correct drift
                cumulative_angle += yaw
                cumulative_angle = correct_drift_v2(cumulative_angle)

                angles.extend([cumulative_angle] * interval)

            except cv2.error as e:
                print(f"OpenCV error at frame {i}: {e}")
                angles.extend([cumulative_angle] * interval)

        print(np.array(angles))

        reference_image = i + interval

    angles = -np.array(angles) * scalar_multiplier
    return frames, angles
