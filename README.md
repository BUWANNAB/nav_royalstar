# Royalstar BlueAnt ROS 2 workspace

ROS 2 Humble navigation workspace for the Royalstar BlueAnt robot.

## Packages

- src/drivers: Livox MID-360 driver
- src/mapping: LIO-SAM, map management, and PCD-to-2D-map conversion
- src/localization: NDT localization
- src/navigation: TF pose conversion and vehicle navigation
- src/hardware: PLC chassis communication

The original source locations used to assemble this workspace are recorded in SOURCE_MANIFEST.txt.

## Build

This repository contains source packages only. Build outputs are intentionally excluded.

    source /opt/ros/humble/setup.bash
    cd ~/blueant_nav_ws
    colcon build --symlink-install
