from launch import LaunchDescription
from launch_ros.actions import Node

def generate_launch_description():
    return LaunchDescription([
        Node(
            package='gnss_converter_by',
            executable='gnss_converter', 
            name='gnss_converter',
            output='screen',
            parameters=[
                {'ref_latitude': 36.67766658268351},  # 北京纬度
                {'ref_longitude': 117.047247615079},  # 北京经度
                {'ref_height': 34.13},  # 参考高度
                {'gnss_offset_x': -0.68},  # GNSS的X轴偏移量
                {'gnss_offset_y': -0.25}  # GNSS的Y轴偏移量
            ],
            #respawn=True,  # 关键配置：启用节点崩溃后自动重启 
            respawn_delay=10  # 可选：重启延迟时间（秒），避免频繁重启            
        )
    ])
