package com.ant.robot.service;

import com.ant.robot.model.domain.Files;
import com.baomidou.mybatisplus.extension.service.IService;

/**
* @author 10345
* @description 针对表【t_files(文件详情)】的数据库操作Service
* @createDate 2024-05-23 10:19:52
*/
public interface FilesService extends IService<Files> {

    void deleteByIdTwo(String id);
}
