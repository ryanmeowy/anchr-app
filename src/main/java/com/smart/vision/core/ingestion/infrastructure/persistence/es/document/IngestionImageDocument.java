package com.smart.vision.core.ingestion.infrastructure.persistence.es.document;

import com.smart.vision.core.common.model.GraphTriple;
import com.smart.vision.core.ingestion.domain.model.OcrStructuredResult;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.List;

/**
 * Ingestion-side ES write model.
 */
@Data
@Document(indexName = "#{@vectorConfig.getReadTargetName()}", createIndex = false)
public class IngestionImageDocument {
    @Id
    @Field(type = FieldType.Long)
    private Long id;

    @Field(type = FieldType.Keyword)
    private String imagePath;

    @Field(type = FieldType.Text, analyzer = "my_ik_analyzer", searchAnalyzer = "my_ik_search_analyzer")
    private String ocrContent;

    @Field(type = FieldType.Dense_Vector, similarity = "dot_product")
    private List<Float> imageEmbedding;

    @Field(type = FieldType.Long)
    private Long createTime;

    @Field(type = FieldType.Keyword)
    private String rawFilename;

    @Field(type = FieldType.Text, analyzer = "my_ik_analyzer", searchAnalyzer = "my_ik_search_analyzer")
    private String fileName;

    @Field(type = FieldType.Keyword)
    private List<String> tags;

    @Field(type = FieldType.Keyword)
    private String fileHash;

    @Field(type = FieldType.Nested)
    private List<GraphTriple> relations;

    @Transient
    private OcrStructuredResult structuredOcr;
}
