package geoRural.dto;

import jakarta.validation.constraints.NotBlank;

public record DescarteRequestDTO(
    @NotBlank(message = "A justificativa é obrigatória para o descarte.")
    String justificativa
) {}