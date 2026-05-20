package com.anchr.core.search.infrastructure.persistence.es.document;

import com.anchr.core.search.interfaces.rest.dto.GraphTripleDTO;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * image doc model for elasticsearch
 *
 * @author Ryan
 * @since 2025/12/15
 */
@Data
@Document(indexName = "#{@vectorConfig.getReadTargetName()}", createIndex = false)
public class ImageDocument {
    /**
     * Image ID (ES Doc ID)
     */
    @Id
    @Field(type = FieldType.Long)
    private Long id;

    /**
     * Image path (relative)
     */
    @Field(type = FieldType.Keyword)
    private String imagePath;

    /**
     * OCR extracted text
     */
    @Field(type = FieldType.Text, analyzer = "my_ik_analyzer", searchAnalyzer = "my_ik_search_analyzer")
    private String ocrContent;

    /**
     * Core vector field.
     * Vector dimension is controlled by app.vector.dimension in profile-specific config.
     */
    @Field(type = FieldType.Dense_Vector, similarity = "dot_product")
    private List<Float> imageEmbedding;

    /**
     * Creation time
     */
    @Field(type = FieldType.Long)
    private Long createTime;

    /**
     * original file name
     */
    @Field(type = FieldType.Keyword)
    private String rawFilename;

    /**
     * file name
     */
    @Field(type = FieldType.Text, analyzer = "my_ik_analyzer", searchAnalyzer = "my_ik_search_analyzer")
    private String fileName;

    /**
     * AI tags
     */
    @Field(type = FieldType.Keyword)
    private List<String> tags;

    /**
     * File fingerprint (MD5)
     */
    @Field(type = FieldType.Keyword)
    private String fileHash;

    /**
     * Graph triples (subject, predicate, object)
     */
    @Field(type = FieldType.Nested)
    private List<GraphTripleDTO> relations;
}
