from launch import LaunchDescription
from launch_ros.actions import Node
from ament_index_python.packages import get_package_share_directory
import os

def generate_launch_description():
    # 获取包的共享目录
    pkg_dir = get_package_share_directory('electric_bin')
    
    # 参数文件路径
    param_file = os.path.join(pkg_dir, 'config', 'electric_bin_params.yaml')
    
    return LaunchDescription([
        Node(
            package='electric_bin',
            executable='electric_bin_node',
            name='electric_bin_node',
            output='screen',
            parameters=[param_file],  # 加载YAML配置文件
            arguments=['--ros-args', '--log-level', 'INFO']
        ),
    ])

