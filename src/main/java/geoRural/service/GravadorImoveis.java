package geoRural.service;

import java.io.ByteArrayInputStream;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import geoRural.service.ValidadorImovel.Resultado;

/**
 * Toda a escrita do processamento do CAR, em SQL direto. Não usa repository.save():
 * com 400 mil imóveis, salvar um por um pelo JPA levaria horas.
 */
@Component
public class GravadorImoveis {

    private static final Logger log = LoggerFactory.getLogger(GravadorImoveis.class);

    public static final String CONJUNTO = "imoveis_rurais";

    /** Tabela de destino gravada na quarentena (registro_quarentena.entidade), a mesma da regra_campo. */
    public static final String ENTIDADE = "imovel_rural";

    /** O que o processamento precisa saber do arquivo, sem passar pelo JPA (funciona fora da requisição). */
    public record ArquivoParaProcessar(long id, String chaveObjeto, String nomeOriginal,
                                       String status, String siglaFonte) {}

    public record VersaoCriada(long id, int versao) {}

    private static final String SQL_MERGE_IMOVEL = """
            MERGE INTO imovel_rural d
            USING (SELECT ? cod_imovel, ? municipio, ? estado, ? area_ha, ? situacao,
                          SDO_GEOMETRY(?, 4326) geometria, ? dataset_versao_id FROM dual) s
            ON (d.cod_imovel = s.cod_imovel)
            WHEN MATCHED THEN UPDATE SET d.municipio = s.municipio, d.estado = s.estado,
                 d.area_ha = s.area_ha, d.situacao = s.situacao, d.geometria = s.geometria,
                 d.dataset_versao_id = s.dataset_versao_id
            WHEN NOT MATCHED THEN INSERT (cod_imovel, municipio, estado, area_ha, situacao,
                 geometria, dataset_versao_id)
                 VALUES (s.cod_imovel, s.municipio, s.estado, s.area_ha, s.situacao,
                         s.geometria, s.dataset_versao_id)
            """;

    // pkg_quality.to_geometry: WKB → SDO_GEOMETRY 4326, e NULL para WKB nulo ou quebrado.
    private static final String SQL_QUARENTENA = """
            INSERT INTO registro_quarentena (execucao_id, numero_linha, campo, motivo, payload,
                   entidade, chave_natural, tipo_erro, valor_recebido, dataset_versao_id, geometria)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, pkg_quality.to_geometry(?))
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GravadorImoveis(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<ArquivoParaProcessar> buscarArquivo(long arquivoId) {
        return jdbc.query("""
                SELECT a.id, a.chave_objeto, a.nome_original, a.status, f.sigla
                  FROM arquivo_bruto a JOIN fonte f ON f.id = a.fonte_id
                 WHERE a.id = ?
                """,
                (rs, n) -> new ArquivoParaProcessar(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)),
                arquivoId).stream().findFirst();
    }

    /**
     * Passa o arquivo para PROCESSANDO. Devolve false se ele já estava sendo processado:
     * o UPDATE condicional impede que duas requisições processem o mesmo arquivo ao mesmo tempo.
     */
    public boolean marcarProcessando(long arquivoId) {
        return jdbc.update("""
                UPDATE arquivo_bruto SET status = 'PROCESSANDO', processado_em = NULL
                 WHERE id = ? AND status <> 'PROCESSANDO'
                """, arquivoId) == 1;
    }

    /**
     * Devolve o arquivo para a fila do Airflow (status RECEBIDO), inclusive para reprocessar um
     * PROCESSADO ou REJEITADO. Devolve false se ele estiver sendo processado agora.
     */
    public boolean marcarRecebido(long arquivoId) {
        return jdbc.update("""
                UPDATE arquivo_bruto SET status = 'RECEBIDO', processado_em = NULL
                 WHERE id = ? AND status <> 'PROCESSANDO'
                """, arquivoId) == 1;
    }

    public long iniciarExecucao(long arquivoId) {
        return inserirComId("""
                INSERT INTO execucao (arquivo_bruto_id, tipo, status)
                VALUES (?, 'VALIDACAO', 'EM_ANDAMENTO')
                """, arquivoId);
    }

    /** Cria a próxima versão do conjunto na zona TRATADA (ainda sem arquivo no bucket, por isso sem chave_objeto). */
    public VersaoCriada criarVersao(long execucaoId) {
        return criarVersao(execucaoId, CONJUNTO);
    }

    /** Mesma coisa para outro conjunto (reserva_legal, focos_calor), que usa o mesmo processamento. */
    public VersaoCriada criarVersao(long execucaoId, String conjunto) {
        Integer versao = jdbc.queryForObject(
                "SELECT NVL(MAX(versao), 0) + 1 FROM dataset_versao WHERE conjunto = ?",
                Integer.class, conjunto);
        long id = inserirComId("""
                INSERT INTO dataset_versao (conjunto, versao, execucao_id, zona)
                VALUES (?, ?, ?, 'TRATADA')
                """, conjunto, versao, execucaoId);
        return new VersaoCriada(id, versao);
    }

    /** Grava um lote de imóveis aprovados. A geometria vai em WKB e vira SDO_GEOMETRY no banco. */
    public void gravarImoveis(long datasetVersaoId, List<Resultado> aprovados) {
        if (aprovados.isEmpty()) {
            return;
        }
        WKBWriter wkbWriter = new WKBWriter();
        jdbc.batchUpdate(SQL_MERGE_IMOVEL, aprovados, aprovados.size(), (PreparedStatement ps, Resultado r) -> {
            byte[] wkb = wkbWriter.write(r.geometria());
            ps.setString(1, r.imovel().codImovel());
            ps.setString(2, r.imovel().municipio());
            ps.setString(3, estado(r.imovel().codImovel()));
            ps.setBigDecimal(4, r.imovel().areaHa());
            ps.setString(5, r.imovel().situacao());
            ps.setBlob(6, new ByteArrayInputStream(wkb), wkb.length);
            ps.setLong(7, datasetVersaoId);
        });
    }

    /**
     * Grava um lote de reprovados. O payload leva as colunas do .dbf, sem a geometria (pode ter
     * milhares de pontos); a geometria vai na coluna própria, como chegou, para o visualizador da
     * quarentena e para o "Revalidar". {@code entidade} é a tabela de destino (imovel_rural,
     * car_reserva_legal) e {@code datasetVersaoId} a versão da carga, que o pkg_tratada usa.
     */
    public void gravarQuarentena(long execucaoId, long datasetVersaoId, String entidade, List<Resultado> reprovados) {
        if (reprovados.isEmpty()) {
            return;
        }
        WKBWriter wkbWriter = new WKBWriter();
        jdbc.batchUpdate(SQL_QUARENTENA, reprovados, reprovados.size(), (PreparedStatement ps, Resultado r) -> {
            String motivo = r.motivoQuarentena();
            String cod = r.imovel().codImovel();
            ps.setLong(1, execucaoId);
            ps.setInt(2, r.imovel().linha());
            ps.setString(3, campo(motivo));
            ps.setString(4, motivo);
            ps.setString(5, json(r));
            ps.setString(6, entidade);
            ps.setString(7, limitar(cod, 100));
            ps.setString(8, tipoErro(motivo));
            ps.setString(9, motivo.startsWith("cod_imovel") ? limitar(cod, 4000) : null);
            ps.setLong(10, datasetVersaoId);
            Geometry g = geometriaRecebida(r.imovel().geometria());
            if (g == null) {
                ps.setNull(11, Types.BLOB);
            } else {
                byte[] wkb = wkbWriter.write(g);
                ps.setBlob(11, new ByteArrayInputStream(wkb), wkb.length);
            }
        });
    }

    public void concluir(long arquivoId, long execucaoId, long datasetVersaoId,
                         int lidos, int validos, int invalidos) {
        jdbc.update("""
                UPDATE execucao SET status = 'CONCLUIDA', finalizada_em = SYSTIMESTAMP,
                       registros_lidos = ?, registros_validos = ?, registros_invalidos = ?
                 WHERE id = ?
                """, lidos, validos, invalidos, execucaoId);
        jdbc.update("UPDATE dataset_versao SET registros = ? WHERE id = ?", validos, datasetVersaoId);
        int substituidos = substituirPendentes(execucaoId);
        if (substituidos > 0) {
            log.info("Execução {}: {} registros abertos de cargas anteriores substituídos na quarentena",
                    execucaoId, substituidos);
        }
        jdbc.update("""
                UPDATE arquivo_bruto SET status = 'PROCESSADO', processado_em = SYSTIMESTAMP
                 WHERE id = ?
                """, arquivoId);
    }

    /**
     * Encerra como SUBSTITUIDO os registros ainda abertos que esta carga resolveu (mesmo arquivo
     * reprocessado, ou o mesmo código de novo): reprocessar não duplica a fila da quarentena.
     * A regra fica no pkg_tratada, a mesma que a DAG do Airflow chama.
     */
    public int substituirPendentes(long execucaoId) {
        Integer total = jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (CallableStatement cs = con.prepareCall("{? = call pkg_tratada.substituir_pendentes(?)}")) {
                cs.registerOutParameter(1, Types.NUMERIC);
                cs.setLong(2, execucaoId);
                cs.execute();
                return cs.getInt(1);
            }
        });
        return total == null ? 0 : total;
    }

    /** Para conjuntos que acumulam (focos): a versão registra só o que trouxe de novo. */
    public void atualizarRegistrosDaVersao(long datasetVersaoId, int registros) {
        jdbc.update("UPDATE dataset_versao SET registros = ? WHERE id = ?", registros, datasetVersaoId);
    }

    public void falhar(long arquivoId, Long execucaoId, String mensagem) {
        if (execucaoId != null) {
            jdbc.update("""
                    UPDATE execucao SET status = 'FALHOU', finalizada_em = SYSTIMESTAMP, mensagem_erro = ?
                     WHERE id = ?
                    """, limitar(mensagem, 4000), execucaoId);
        }
        jdbc.update("""
                UPDATE arquivo_bruto SET status = 'REJEITADO', processado_em = SYSTIMESTAMP
                 WHERE id = ?
                """, arquivoId);
    }

    private long inserirComId(String sql, Object... parametros) {
        KeyHolder chave = new GeneratedKeyHolder();
        jdbc.update(con -> {
            // No Oracle é preciso dizer qual coluna devolver; sem isso vem o ROWID
            PreparedStatement ps = con.prepareStatement(sql, new String[] {"ID"});
            for (int i = 0; i < parametros.length; i++) {
                if (parametros[i] == null) {
                    ps.setNull(i + 1, Types.NULL);
                } else {
                    ps.setObject(i + 1, parametros[i]);
                }
            }
            return ps;
        }, chave);
        return chave.getKey().longValue();
    }

    /** Campo da quarentena pelo começo do motivo: "num_area vazio" → area_ha. */
    static String campo(String motivo) {
        if (motivo.startsWith("cod_imovel")) {
            return "cod_imovel";
        }
        if (motivo.startsWith("num_area")) {
            return "area_ha";
        }
        return "geometria";
    }

    /** Código da V10 (tabela tipo_erro) pelo começo do motivo; null quando não há um equivalente. */
    static String tipoErro(String motivo) {
        if (motivo.startsWith("cod_imovel vazio") || motivo.startsWith("num_area vazio")) {
            return "CAMPO_OBRIGATORIO_AUSENTE";
        }
        if (motivo.startsWith("cod_imovel duplicado")) {
            return "CHAVE_NATURAL_DUPLICADA";
        }
        if (motivo.startsWith("sem geometria")) {
            return "GEOMETRIA_NULA";
        }
        if (motivo.startsWith("geometria não é polígono")) {
            return "GEOMETRIA_TIPO_INCOMPATIVEL";
        }
        if (motivo.startsWith("geometria inválida") || motivo.startsWith("geometria irreparável")) {
            return "GEOMETRIA_INVALIDA";
        }
        return null;
    }

    /**
     * A geometria do registro reprovado, como chegou. Se ela for válida (o registro caiu por outro
     * motivo, como o código duplicado), já vai no sentido que o Oracle exige, para o "Revalidar"
     * conseguir gravá-la; se não for, vai como está, para o operador ver o problema no mapa.
     */
    static Geometry geometriaRecebida(Geometry original) {
        if (original == null || original.isEmpty()) {
            return null;
        }
        Geometry pronta = ValidadorImovel.prepararGeometria(original);
        return pronta != null ? pronta : original;
    }

    /** UF pelo prefixo do código do CAR: "PR-4113700-..." → "PR". */
    static String estado(String codImovel) {
        if (codImovel != null && codImovel.length() > 3 && codImovel.charAt(2) == '-') {
            return codImovel.substring(0, 2).toUpperCase();
        }
        return null;
    }

    private String json(Resultado r) {
        try {
            return objectMapper.writeValueAsString(r.imovel().atributos());
        } catch (JsonProcessingException e) {
            return String.valueOf(r.imovel().atributos());
        }
    }

    private static String limitar(String texto, int max) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= max ? texto : texto.substring(0, max);
    }
}
