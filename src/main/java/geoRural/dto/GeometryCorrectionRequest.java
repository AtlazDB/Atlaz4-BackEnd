package geoRural.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/** A geometria que o operador salvou na tela (o "geometry" de um GeoJSON) e, opcional, o porquê. */
public record GeometryCorrectionRequest(
    @NotNull(message = "A geometria corrigida é obrigatória.")
    @JsonProperty("geometria") Map<String, Object> geometry,
    @JsonProperty("justificativa") String justification
) {}
