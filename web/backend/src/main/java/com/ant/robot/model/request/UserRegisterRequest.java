package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;

/**
 * @author ChenWeihan
 */
@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = -504281901178319625L;
    private String userAccount;
    private String userPassword;
    private String checkPassword;
}