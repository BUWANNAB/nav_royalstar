import os
from launch import LaunchDescription
from launch_ros.actions import Node
from ament_index_python.packages import get_package_share_directory

def generate_launch_description():
    pkg_name = 'parameter_server'
    share_dir = get_package_share_directory(pkg_name)
    
    # 定义配置文件路径
    # 1. 节点映射文件 (定义哪些参数发给哪些节点)
    mapping_file_name = 'node_parameter_mapping.yaml'
    mapping_file_path = os.path.join(share_dir, 'config', mapping_file_name)
    
    # 2. 参数元数据文件 (定义参数类型、默认值，新增)
    metadata_file_name = 'parameter_metadata.yaml'
    metadata_file_path = os.path.join(share_dir, 'config', metadata_file_name)

    # 严格检查文件是否存在，避免节点启动后因找不到配置而崩溃
    if not os.path.exists(mapping_file_path):
        raise FileNotFoundError(f"Critical error: Node mapping file not found at {mapping_file_path}")
    
    if not os.path.exists(metadata_file_path):
        raise FileNotFoundError(f"Critical error: Parameter metadata file not found at {metadata_file_path}")

    # 创建参数服务器节点
    parameter_server_node = Node(
        package=pkg_name,
        executable='parameter_server_node',  # 确保与 CMakeLists.txt 中安装的 executable 名称一致
        name='parameter_distributor',        # 节点名称
        output='screen',                     # 输出日志到屏幕
        
        # 传递配置文件路径给节点
        parameters=[
            # 核心配置：映射文件路径
            {'mapping_file': mapping_file_path},
            # 新增核心配置：元数据文件路径
            {'metadata_file': metadata_file_path},
            
            # 数据库配置 (建议在此处或通过 env 变量覆盖默认值)
            {'db_host': 'localhost'},
            {'db_user': 'root'},
            {'db_password': 'root'}, # 生产环境建议使用环境变量
            {'db_name': 'db_ant'},
            
            # 业务逻辑配置
            {'auto_refresh': True},
            {'refresh_interval': 30.0}, # 浮点数
        ],
        
        # 话题重映射 (如有需要)
        remappings=[
            ('~/update_parameters', '/global/update_parameters'), # 示例：将局部服务名映射为全局
        ]
    )

    return LaunchDescription([
        parameter_server_node
    ])