#!/bin/bash

# 增强版ROS2节点关闭脚本

# 要关闭的节点列表（不加斜杠）
NODES=(
    "lio_sam_featureExtraction"
    "lio_sam_imageProjection"
    "lio_sam_mapOptimization"
    "lio_sam_imuPreintegration"
    "robot_state_publisher"
    "transform_listener_impl"
    "static_transform_publisher"
)

echo "===== ROS2节点关闭脚本 ====="
echo "当前时间: $(date)"
echo ""

# 获取当前运行的节点列表
echo "当前运行的ROS2节点:"
ros2 node list
echo ""

# 函数：通过PID关闭节点
kill_node_by_name() {
    local node_name=$1
    local full_node_name="/$node_name"
    
    echo "处理节点: $full_node_name"
    
    # 先确认节点在线（ROS2 无在线 kill 命令）
    if ros2 node list 2>/dev/null | grep -q "$full_node_name"; then
        # 旧实现的 "ros2 node kill" 在 ROS2 中不存在，已移除，直接走进程级关闭
        
        # 等待一下
        sleep 0.5
        
        # 检查是否还在运行
        if ros2 node list 2>/dev/null | grep -q "$full_node_name"; then
            echo "  节点在线，使用进程信号关闭..."
            
            # 方法2: 通过系统进程查找
            echo "  查找相关进程..."
            
            # 查找包含节点名的进程
            PIDS=$(ps aux | grep "$node_name" | grep -v grep | grep -v "$0" | awk '{print $2}')
            
            for pid in $PIDS; do
                echo "  找到进程PID: $pid，发送SIGINT信号"
                kill -SIGINT "$pid" 2>/dev/null
                sleep 0.2
            done
            
            # 再次检查
            sleep 1
            if ros2 node list 2>/dev/null | grep -q "$full_node_name"; then
                echo "  ✗ 节点 $full_node_name 仍然在运行"
                return 1
            else
                echo "  ✓ 节点 $full_node_name 已关闭"
                return 0
            fi
        else
            echo "  ✓ 节点 $full_node_name 已关闭"
            return 0
        fi
    else
        echo "  ⓘ 节点 $full_node_name 未运行"
        return 0
    fi
}

# 主关闭循环
FAILED_NODES=()
for NODE in "${NODES[@]}"; do
    echo "----------------------------------------"
    if ! kill_node_by_name "$NODE"; then
        FAILED_NODES+=("$NODE")
    fi
    echo ""
done

# 显示结果
echo "===== 关闭结果 ====="
if [ ${#FAILED_NODES[@]} -eq 0 ]; then
    echo "✓ 所有指定节点已成功关闭"
else
    echo "✗ 以下节点关闭失败:"
    for node in "${FAILED_NODES[@]}"; do
        echo "  - $node"
    done
fi

echo -e "\n最终节点列表:"
ros2 node list