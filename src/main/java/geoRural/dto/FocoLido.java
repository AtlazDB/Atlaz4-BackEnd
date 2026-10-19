package geoRural.dto;

import java.util.Map;

/**
 * Uma linha do CSV de focos do INPE, como veio, antes de qualquer validação. Os campos ficam em
 * texto porque converter é trabalho do validador. {@code atributos} guarda a linha inteira para a
 * quarentena.
 */
public record FocoLido(int linha, String idInpe, String lat, String lon, String dataHora,
                       String satelite, String municipio, String codIbge, String estado,
                       String bioma, String frp, Map<String, String> atributos) {}
