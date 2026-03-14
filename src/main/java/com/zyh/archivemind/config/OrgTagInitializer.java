package com.zyh.archivemind.config;

import com.zyh.archivemind.model.OrganizationTag;
import com.zyh.archivemind.model.User;
import com.zyh.archivemind.repository.OrganizationTagRepository;
import com.zyh.archivemind.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 组织标签初始化器
 * 在应用启动时自动创建默认组织标签（如果不存在）
 */
@Component
@Order(2) // 设置优先级，确保在管理员账号初始化器之后运行
public class OrgTagInitializer implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(OrgTagInitializer.class);
    
    private static final String DEFAULT_TAG = "default";
    private static final String DEFAULT_NAME = "默认组织";
    private static final String DEFAULT_DESCRIPTION = "系统默认组织标签，自动分配给所有新用户";

    private static final String ADMIN_TAG = "admin";
    private static final String ADMIN_NAME = "管理员组织";
    private static final String ADMIN_DESCRIPTION = "管理员专用组织标签，具有管理权限";

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Override
    public void run(String... args) throws Exception {
        // 查找管理员用户
        User adminUser = userRepository.findByUsername(adminUsername)
                .orElseThrow(() -> new RuntimeException("管理员账号未找到，无法创建组织标签"));

        // 创建默认组织标签
        createOrganizationTagIfNotExists(DEFAULT_TAG, DEFAULT_NAME, DEFAULT_DESCRIPTION, adminUser);
        
        // 创建管理员组织标签
        createOrganizationTagIfNotExists(ADMIN_TAG, ADMIN_NAME, ADMIN_DESCRIPTION, adminUser);
        
        logger.info("组织标签初始化完成");
    }
    
    /**
     * 如果组织标签不存在，则创建
     */
    private void createOrganizationTagIfNotExists(String tagId, String name, String description, User creator) {
        logger.info("检查组织标签是否存在: {}", tagId);
        if (!organizationTagRepository.existsByTagId(tagId)) {
            logger.info("创建组织标签: {}", tagId);
            OrganizationTag tag = new OrganizationTag();
            tag.setTagId(tagId);
            tag.setName(name);
            tag.setDescription(description);
            tag.setCreatedBy(creator);
            organizationTagRepository.save(tag);
            logger.info("组织标签 '{}' 创建成功", tagId);
        } else {
            logger.info("组织标签 '{}' 已存在，跳过创建步骤", tagId);
        }
    }
} 