package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserLoginRequest implements Serializable {

    private static final long serialVersionUID = -1255496593353870641L;

    private String userAccount;
    private String userPassword;
}
