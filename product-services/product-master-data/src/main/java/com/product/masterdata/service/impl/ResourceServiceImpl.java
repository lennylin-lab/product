package com.product.masterdata.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.product.masterdata.domain.entity.Resource;
import com.product.masterdata.mapper.ResourceMapper;
import com.product.masterdata.service.IResourceService;
import org.springframework.stereotype.Service;

/**
 * @Auther: chuan
 * @Date: 2026/1/1 - 01 - 01 - 02:40
 * @Description: com.product.masterdata.resource.service.impl
 * @version: 1.0
 */
@Service
public class ResourceServiceImpl extends ServiceImpl<ResourceMapper, Resource> implements IResourceService {
}
