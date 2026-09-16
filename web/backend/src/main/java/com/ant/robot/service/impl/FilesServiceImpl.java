package com.ant.robot.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ant.robot.model.domain.Files;
import com.ant.robot.service.FilesService;
import com.ant.robot.mapper.FilesMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
* @author 10345
* @description 针对表【t_files(文件详情)】的数据库操作Service实现
* @createDate 2024-05-23 10:19:52
*/
@Service
public class FilesServiceImpl extends ServiceImpl<FilesMapper, Files>
    implements FilesService{

    @Resource
    private FilesMapper filesMapper;

    @Override
    public void deleteByIdTwo(String id) {
        filesMapper.deleteByIdTwo(id);
    }
}




