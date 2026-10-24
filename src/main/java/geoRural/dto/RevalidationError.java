package geoRural.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Um erro que o pkg_quality.revalidar_registro encontrou no registro. */
public record RevalidationError(
    @JsonProperty("tipo_erro") String errorType,
    @JsonProperty("campo") String field,
    @JsonProperty("motivo") String reason,
    @JsonProperty("valor_recebido") String receivedValue
) {}
