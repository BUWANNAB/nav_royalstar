from launch import LaunchDescription
from launch_ros.actions import Node


def generate_launch_description():
    return LaunchDescription([
        Node(
            package='build_map_manager',
            executable='build_map_manager_node',
            name='build_map_manager',
            output='screen',
            parameters=[{
                'buildmap_topic': '/buildmap',
                'status_topic': '/buildmap_status',
                'cloud_topic': '/lio_sam/mapping/cloud_registered',
                'save_map_service': '/lio_sam/save_map',
                'workspace_setup': '/home/lyrobot004/lio_sam_test/install/setup.bash',
                'map_base_dir': '/home/lyrobot004/inHome/pcd',
                'livox_launch_cmd': 'ros2 launch livox_ros_driver2 msg_MID360_launch.py',
                'lio_sam_launch_cmd': 'ros2 launch lio_sam run.launch.py',
                'map_resolution': 0.1,
                'start_delay_sec': 3.0,
                'save_timeout_sec': 300.0,
                'stop_after_save': True,
            }]
        )
    ])
