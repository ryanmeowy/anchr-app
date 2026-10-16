package com.anchr.core.common.constant;

/**
 * Segment 索引与别名名称。
 *
 * <p>三个 {@code public static final String} 常量是编译期字面量，供 Spring Data
 * Elasticsearch 的 {@code @Document(indexName = ...)} 注解使用（注解值必须是编译期常量）。
 * 运行期一律通过 {@link #indexName()}、{@link #readAlias()}、{@link #writeAlias()} 取值：
 * 当设置系统属性 {@code -Danchr.segment.index=<name>} 或环境变量
 * {@code ANCHR_SEGMENT_INDEX=<name>} 时，读取别名为 {@code <name>_read}、写入别名为
 * {@code <name>_write}、物理索引前缀为 {@code <name>}；未设置时行为与默认常量完全一致。
 * 评测环境因此可以与云端实例共用同一套 ES 集群而互不干扰。
 */
public final class SegmentIndexConstant {

    public static final String INDEX_NAME = "kb_segment";
    public static final String READ_ALIAS = "kb_segment_read";
    public static final String WRITE_ALIAS = "kb_segment_write";

    public static final String INDEX_OVERRIDE_PROPERTY = "anchr.segment.index";
    public static final String INDEX_OVERRIDE_ENV = "ANCHR_SEGMENT_INDEX";

    private static final String READ_SUFFIX = "_read";
    private static final String WRITE_SUFFIX = "_write";

    private SegmentIndexConstant() {
    }

    /** 物理索引前缀：默认 {@code kb_segment}。 */
    public static String indexName() {
        String override = override();
        return override == null ? INDEX_NAME : override;
    }

    /** 读取别名：默认 {@code kb_segment_read}。 */
    public static String readAlias() {
        String override = override();
        return override == null ? READ_ALIAS : override + READ_SUFFIX;
    }

    /** 写入别名：默认 {@code kb_segment_write}。 */
    public static String writeAlias() {
        String override = override();
        return override == null ? WRITE_ALIAS : override + WRITE_SUFFIX;
    }

    private static String override() {
        String property = System.getProperty(INDEX_OVERRIDE_PROPERTY);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String environment = System.getenv(INDEX_OVERRIDE_ENV);
        if (environment != null && !environment.isBlank()) {
            return environment.trim();
        }
        return null;
    }
}
