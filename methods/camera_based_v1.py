def correct_drift_v1(cumulative_angle, drift_threshold=5, correction_factor=0.001):
    if abs(cumulative_angle) < drift_threshold:
        return cumulative_angle

    correction = correction_factor * (abs(cumulative_angle) - drift_threshold)
    return cumulative_angle - np.sign(cumulative_angle) * correction


def track_keypoints_v1(frames, keypoints_descriptors, interval=20, scalar_multiplier=3.5):
    lk_params = dict(winSize=(15, 15),
                     maxLevel=2,
                     criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 10, 0.03))

    angles = []
    total_frames = len(frames)
    cumulative_angle = 0  # Initialize cumulative angle
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

        # Check if we have enough points
        if len(good_old) < 3 or len(good_new) < 3:
            print(f"Skipping frame {i} due to insufficient keypoints")
            angles.extend([cumulative_angle] * interval)
        else:
            try:
                matrix, inliers = cv2.estimateAffinePartial2D(good_old, good_new, method=cv2.RANSAC,
                                                              ransacReprojThreshold=3.0)
                if matrix is None:
                    print(f"Skipping frame {i} due to transformation estimation failure")
                    angles.extend([cumulative_angle] * interval)
                else:
                    angle = -np.arctan2(matrix[0, 1], matrix[0, 0]) * 180 / np.pi * scalar_multiplier
                    cumulative_angle += angle  # Accumulate the angle

                    # drift correction
                    cumulative_angle = correct_drift_v1(cumulative_angle)
                    angles.extend([cumulative_angle] * interval)
            except cv2.error as e:
                print(f"Skipping frame {i} due to OpenCV error: {e}")
                angles.extend([cumulative_angle] * interval)

        # Annotate frames with the cumulative angle
        first_frame = i
        last_frame = min(i + interval, total_frames)
        for j in range(first_frame, last_frame):
            frames[j] = annotate_frame_with_angle(frames[j], cumulative_angle)
            visualize_keypoints(frames[j], keypoints_descriptors[j][0])

        # Update reference image for next iteration
        reference_image = i + interval

    return frames, angles