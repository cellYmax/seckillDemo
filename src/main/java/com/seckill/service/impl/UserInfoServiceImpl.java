package com.seckill.service.impl;

import com.seckill.entity.UserInfo;
import com.seckill.mapper.UserInfoMapper;
import com.seckill.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
