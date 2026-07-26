package com.travel.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.Instant;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 自动填充处理器：在 INSERT / UPDATE 时自动写入审计时间字段。
 *
 * <ul>
 *   <li>{@code createdAt}：仅在 INSERT 时填充</li>
 *   <li>{@code updatedAt}：在 INSERT 和 UPDATE 时均填充</li>
 * </ul>
 *
 * 实体字段需标注：
 * <pre>
 *   @TableField(fill = FieldFill.INSERT)
 *   private Instant createdAt;
 *
 *   @TableField(fill = FieldFill.INSERT_UPDATE)
 *   private Instant updatedAt;
 * </pre>
 *
 * @author Hollis
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        this.strictInsertFill(metaObject, "createdAt", Instant.class, now);
        this.strictInsertFill(metaObject, "updatedAt", Instant.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", Instant.class, Instant.now());
    }
}
