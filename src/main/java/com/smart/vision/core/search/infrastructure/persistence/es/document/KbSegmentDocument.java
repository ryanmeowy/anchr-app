package com.smart.vision.core.search.infrastructure.persistence.es.document;

import com.smart.vision.core.search.domain.model.Bbox;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * Unified KB segment document for text and image retrieval in Phase 1.
 */
@Data
@Document(indexName = "#{@kbSegmentConfig.getReadTargetName()}", createIndex = false)
public class KbSegmentDocument {

    @Id
    @Field(type = FieldType.Keyword)
    private String segmentId;

    @Field(type = FieldType.Keyword)
    private String assetId;

    @Field(type = FieldType.Keyword)
    private String assetType;

    @Field(type = FieldType.Keyword)
    private String segmentType;

    @Field(type = FieldType.Text, analyzer = "my_ik_analyzer", searchAnalyzer = "my_ik_search_analyzer")
    private String title;

    @Field(type = FieldType.Text, analyzer = "my_ik_analyzer", searchAnalyzer = "my_ik_search_analyzer")
    private String contentText;

    @Field(type = FieldType.Text, analyzer = "my_ik_analyzer", searchAnalyzer = "my_ik_search_analyzer")
    private String ocrText;

    @Field(type = FieldType.Integer)
    private Integer pageNo;

    @Field(type = FieldType.Integer)
    private Integer chunkOrder;

    @Field(type = FieldType.Object)
    private Bbox bbox;

    @Field(type = FieldType.Integer)
    private Integer imageWidth;

    @Field(type = FieldType.Integer)
    private Integer imageHeight;

    @Field(type = FieldType.Dense_Vector, similarity = "dot_product")
    private List<Float> embedding;

    @Field(type = FieldType.Keyword)
    private String sourceRef;

    @Field(type = FieldType.Keyword)
    private String thumbnail;

    @Field(type = FieldType.Text, analyzer = "my_ik_analyzer", searchAnalyzer = "my_ik_search_analyzer")
    private String ocrSummary;

    @Field(type = FieldType.Keyword)
    private List<String> tags;

    @Field(type = FieldType.Long)
    private Long createdAt;
}
