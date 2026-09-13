import os
from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, SetEnvironmentVariable
from launch.substitutions import LaunchConfiguration, PathJoinSubstitution
from launch_ros.actions import Node
from launch.conditions import IfCondition

def generate_launch_description():
    # 获取功能包共享目录
    pkg_share = get_package_share_directory('gtsam_fusion')
    
    # 声明启动参数
    use_sim_time = DeclareLaunchArgument(
        'use_sim_time',
        default_value='false',
        description='Use simulation (Gazebo) clock if true'
    )
    
    config_file = DeclareLaunchArgument(
        'config_file',
        default_value=PathJoinSubstitution([pkg_share, 'config', 'fusion_params.yaml']),
        description='Path to config file'
    )
    
    enable_rviz = DeclareLaunchArgument(
        'enable_rviz',
        default_value='false',
        description='Enable RViz2 visualization'
    )
    
    enable_test_node = DeclareLaunchArgument(
        'enable_test_node',
        default_value='false',
        description='Enable test data publisher node'
    )
    
    # 主融合节点
    fusion_node = Node(
        package='gtsam_fusion',
        executable='gtsam_fusion_node',
        name='gtsam_fusion_node',
        output='screen',
        parameters=[LaunchConfiguration('config_file'), {
            'use_sim_time': LaunchConfiguration('use_sim_time')
        }],
        remappings=[
            ('/fused_pose', '/fused_odom/pose'),
            ('/fused_path', '/fused_odom/path')
        ]
    )
    
    # RViz2节点
    rviz_config_file = PathJoinSubstitution([pkg_share, 'config', 'fusion_rviz.rviz'])
    rviz_node = Node(
        package='rviz2',
        executable='rviz2',
        name='rviz2',
        arguments=['-d', rviz_config_file],
        condition=IfCondition(LaunchConfiguration('enable_rviz')),
        parameters=[{'use_sim_time': LaunchConfiguration('use_sim_time')}]
    )
    
    # 测试数据发布节点（可选）
    test_publisher = Node(
        package='gtsam_fusion',
        executable='test_publisher',
        name='test_data_publisher',
        output='screen',
        condition=IfCondition(LaunchConfiguration('enable_test_node')),
        parameters=[{'use_sim_time': LaunchConfiguration('use_sim_time')}]
    )
    
    # 可选的robot_localization EKF节点（用于对比）
    ekf_filter_node = Node(
        package='robot_localization',
        executable='ekf_node',
        name='ekf_filter_node',
        output='screen',
        parameters=[PathJoinSubstitution([pkg_share, 'config', 'ekf_params.yaml']), {
            'use_sim_time': LaunchConfiguration('use_sim_time')
        }],
        remappings=[
            ('odometry/filtered', '/ekf_odom'),
            ('/odom0', '/gnss/odom'),
            ('/odom1', '/lidar/pose')
        ]
    )
    
    # 添加静态TF变换：fusion_link → base_link
    static_tf_base_to_lidar = Node(
        package='tf2_ros',
        executable='static_transform_publisher',
        name='base_to_lidar_tf',
        arguments=['-0.32', '0.0', '0.0', '0.0', '0.0', '0.0', 'fusion_link', 'base_link']
    )
    
    return LaunchDescription([
        # 设置环境变量
        SetEnvironmentVariable('RCUTILS_COLORIZED_OUTPUT', '1'),
        
        # 声明参数
        use_sim_time,
        config_file,
        enable_rviz,
        enable_test_node,
        
        # 启动节点
        static_tf_base_to_lidar,
        fusion_node,
        rviz_node,
        # test_publisher,  # 按需启用
        # ekf_filter_node, # 按需启用
    ])
