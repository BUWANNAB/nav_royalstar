package com.ant.robot.mapper;

import com.ant.robot.model.domain.Files;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
* @author 10345
* @description 针对表【t_files(文件详情)】的数据库操作Mapper
* @createDate 2024-05-23 10:19:52
* @Entity com.ant.robot.model.domain.Files
*/
public interface FilesMapper extends BaseMapper<Files> {

    @Update("update t_files set isDelete = 2 where id = #{id}")
    Boolean deleteByIdTwo(String id);


    @Select("select * from t_files")
    List<Files> queryFilesListAll();

    /**
     * 批量逻辑恢复
     * @param ids 文件ID列表
     */
    void updateBatchByIdZero(List<String> ids);
}




