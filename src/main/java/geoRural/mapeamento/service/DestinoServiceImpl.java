package geoRural.mapeamento.service;

import geoRural.exception.RequisicaoInvalidaException;
import geoRural.mapeamento.dto.DestinoResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Os destinos vêm de uma lista fechada de tabelas (as que o pkg_tratada sabe gravar e os
 * indicadores leem), e os campos de cada uma vêm do próprio banco (user_tab_columns): coluna
 * nova numa migration aparece sozinha na tela de mapeamento.
 */
@Service
public class DestinoServiceImpl implements DestinoService {

    /** Tabela → rótulo da tela, na ordem em que aparecem. */
    private static final Map<String, String> TABELAS = new LinkedHashMap<>();

    static {
        TABELAS.put("imovel_rural", "Imóveis rurais (CAR)");
        TABELAS.put("car_reserva_legal", "Reserva Legal (CAR)");
        TABELAS.put("foco_calor", "Focos de calor");
        TABELAS.put("municipio", "Municípios");
    }

    /**
     * Colunas que o mapeamento não preenche: a chave técnica, a versão (o pkg_tratada põe a da
     * carga), a data de criação e o vínculo com o município (etapa de cruzamento).
     */
    private static final Set<String> FORA_DO_MAPEAMENTO = Set.of("id", "dataset_versao_id", "criado_em", "municipio_id");

    /** Preenchidas pelo pkg_tratada quando não são mapeadas: não são obrigatórias no mapeamento. */
    private static final Map<String, String> DERIVADAS = Map.of(
            "imovel_rural.estado", "Preenchido sozinho pelo prefixo do código do imóvel (PR-...), se não for mapeado.",
            "municipio.estado", "Preenchido sozinho pelos dois primeiros dígitos do código IBGE, se não for mapeado.");

    private final JdbcTemplate jdbc;

    public DestinoServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DestinoResponse> listar() {
        String marcadores = TABELAS.keySet().stream().map(t -> "?").collect(Collectors.joining(", "));
        Map<String, List<DestinoResponse.Campo>> campos = new LinkedHashMap<>();
        TABELAS.keySet().forEach(t -> campos.put(t, new ArrayList<>()));

        jdbc.query("""
                SELECT LOWER(table_name) tabela, LOWER(column_name) coluna, data_type, data_scale, nullable
                  FROM user_tab_columns
                 WHERE LOWER(table_name) IN (%s)
                 ORDER BY table_name, column_id""".formatted(marcadores),
                linha -> {
                    String tabela = linha.getString("tabela");
                    String coluna = linha.getString("coluna");
                    if (FORA_DO_MAPEAMENTO.contains(coluna)) {
                        return;
                    }
                    String observacao = DERIVADAS.get(tabela + "." + coluna);
                    boolean obrigatorio = "N".equals(linha.getString("nullable")) && observacao == null;
                    campos.get(tabela).add(new DestinoResponse.Campo(coluna,
                            tipo(linha.getString("data_type"), (Integer) linha.getObject("data_scale", Integer.class)),
                            obrigatorio, observacao));
                },
                TABELAS.keySet().toArray());

        return TABELAS.entrySet().stream()
                .map(e -> new DestinoResponse(e.getKey(), e.getValue(), campos.get(e.getKey())))
                .toList();
    }

    @Override
    public void validar(String tabela, String campo) {
        DestinoResponse destino = listar().stream()
                .filter(d -> d.tabela().equals(tabela))
                .findFirst()
                .orElseThrow(() -> new RequisicaoInvalidaException(
                        "Tabela de destino inválida. Use: " + String.join(", ", TABELAS.keySet()) + ".",
                        List.of("destinoTabela")));
        boolean existe = destino.campos().stream().anyMatch(c -> c.nome().equals(campo));
        if (!existe) {
            String validos = destino.campos().stream().map(DestinoResponse.Campo::nome).collect(Collectors.joining(", "));
            throw new RequisicaoInvalidaException(
                    "A tabela " + tabela + " não tem o campo " + campo + ". Campos: " + validos + ".",
                    List.of("campoDestino"));
        }
    }

    /** O tipo do Oracle no vocabulário da regra_campo. */
    private static String tipo(String tipoOracle, Integer escala) {
        if (tipoOracle == null) {
            return "TEXTO";
        }
        if (tipoOracle.equals("SDO_GEOMETRY")) {
            return "GEOMETRIA";
        }
        if (tipoOracle.equals("NUMBER")) {
            return escala != null && escala == 0 ? "INTEIRO" : "DECIMAL";
        }
        if (tipoOracle.equals("DATE") || tipoOracle.startsWith("TIMESTAMP")) {
            return "DATA";
        }
        return "TEXTO";
    }
}
