import pandas as pd
import numpy as np
import matplotlib.pyplot as plt


def calculate_yaw_angle(gyro_file, mag_file, alpha=0.998):
    # Load the CSV data
    gyro_data = pd.read_csv(gyro_file)
    mag_data = pd.read_csv(mag_file)

    # Extract timestamps and convert to seconds
    gyro_time = gyro_data['relative_timestamp'].values / 1000
    mag_time = mag_data['relative_timestamp'].values / 1000

    # Extract gyroscope z-axis data (using y-axis as in your code)
    gyro_z = gyro_data['y'].values

    # Extract magnetometer data
    mag_x = mag_data['x'].values
    mag_y = -mag_data['z'].values

    # Calculate magnetic heading and normalize from initial position
    mag_yaw = np.arctan2(-mag_y, mag_x)
    initial_heading = mag_yaw[0]

    # Apply unwrapping to avoid 2π jumps
    mag_yaw = np.unwrap(mag_yaw - initial_heading)

    # Convert to degrees
    mag_yaw_deg = mag_yaw * 180 / np.pi

    # Integrate gyroscope data to get yaw angle
    gyro_yaw = np.zeros_like(gyro_time)

    for i in range(1, len(gyro_time)):
        dt = gyro_time[i] - gyro_time[i - 1]
        gyro_yaw[i] = gyro_yaw[i - 1] + gyro_z[i] * dt

    print(dt)

    # Convert to degrees
    gyro_yaw_deg = gyro_yaw * 180 / np.pi

    # Interpolate magnetometer yaw to match gyroscope timestamps
    mag_yaw_interp = np.interp(gyro_time, mag_time, mag_yaw_deg)

    # Apply complementary filter
    # Initialize fused yaw at zero
    fused_yaw = np.zeros_like(gyro_time)

    # Apply complementary filter with unwrapped angles
    for i in range(1, len(gyro_time)):
        dt = gyro_time[i] - gyro_time[i - 1]
        gyro_delta = gyro_z[i] * dt * 180 / np.pi

        # Calculate drift between sensors in a way that handles wrapping
        mag_diff = mag_yaw_interp[i] - mag_yaw_interp[i - 1]
        gyro_diff = gyro_delta

        # Apply fusion with minimal magnetometer influence (just enough for drift correction)
        fused_yaw[i] = fused_yaw[i - 1] + alpha * gyro_diff + (1 - alpha) * mag_diff

    return {
        'time': gyro_time,
        'gyro_yaw': gyro_yaw_deg,
        'mag_yaw': mag_yaw_interp,
        'fused_yaw': fused_yaw
    }


def plot_yaw_results(results):
    from scipy.signal import savgol_filter
    smoothed_angles = savgol_filter(results['fused_yaw'], window_length=21, polyorder=3)

    time = results['time']

    plt.figure(figsize=(12, 6))
    # plt.plot(time, results['gyro_yaw'], 'r-', alpha=0.5, label='Gyroscope Only')
    # plt.plot(time, results['mag_yaw'], 'b-', alpha=0.5, label='Magnetometer Only')
    plt.plot(time, smoothed_angles)
    plt.xlabel('Time (s)')
    plt.ylabel('Angles')
    plt.title('Sensor based angle calculation result')
    # plt.legend()
    plt.grid(True)
    plt.savefig(f'D:\\res_project_rotation\\my_videos\\figures\\sensor\\{video_idx}_Video-{timestamp}.mp4.png')
    plt.show()


# Rest of your code remains the same
def save_results(results, output_file='yaw_fusion.csv'):
    # Create DataFrame from results
    df = pd.DataFrame({
        'time': results['time'],
        'gyro_yaw': results['gyro_yaw'],
        'mag_yaw': results['mag_yaw'],
        'fused_yaw': results['fused_yaw']
    })

    # Save to CSV
    df.to_csv(output_file, index=False)
    print(f"Results saved to {output_file}")


def calibrate_magnetometer(mag_data, output_file='calibrated_magnetometer.csv'):
    """
    Simple hard-iron calibration for magnetometer
    """
    # Extract magnetometer data
    mag_x = mag_data['x'].values
    mag_y = mag_data['y'].values
    mag_z = mag_data['z'].values

    # Calculate offsets (hard-iron calibration)
    offset_x = (np.max(mag_x) + np.min(mag_x)) / 2
    offset_y = (np.max(mag_y) + np.min(mag_y)) / 2
    offset_z = (np.max(mag_z) + np.min(mag_z)) / 2

    # Apply calibration
    calibrated_mag_data = mag_data.copy()
    calibrated_mag_data['x'] = mag_x - offset_x
    calibrated_mag_data['y'] = mag_y - offset_y
    calibrated_mag_data['z'] = mag_z - offset_z

    # Save calibrated data
    calibrated_mag_data.to_csv(output_file, index=False)
    print(f"Calibrated magnetometer data saved to {output_file}")

    return calibrated_mag_data


if __name__ == "__main__":
    # Paths to your CSV files
    # gyro_file = 'gyroscope.csv'
    # mag_file = 'magnetometer.csv'  # Assuming you have this file with x,y,z columns

    # Load magnetometer data for calibration
    mag_data = pd.read_csv(mag_file)

    # Calibrate magnetometer (optional but recommended)
    calibrated_mag_data = calibrate_magnetometer(mag_data)
    calibrated_mag_file = 'calibrated_magnetometer.csv'

    # Calculate yaw angle using both sensors
    results = calculate_yaw_angle(gyro_file, calibrated_mag_file)

    # Plot results
    plot_yaw_results(results)

    # Save results
    save_results(results)