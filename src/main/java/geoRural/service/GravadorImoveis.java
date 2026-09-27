package geoRural.service;

import java.io.ByteArrayInputStream;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.locationtech.jts.io.WKBWriter;
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

    public static final String CONJUNTO = "imoveis_rurais";

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

    private static final String SQL_QUARENTENA = """
            INSERT INTO registro_quarentena (execucao_id, numero_linha, campo, motivo, payload)
            VALUES (?, ?, ?, ?, ?)
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

    public long iniciarExecucao(long arquivoId) {
        return inserirComId("""
                INSERT INTO execucao (arquivo_bruto_id, tipo, status)
                VALUES (?, 'VALIDACAO', 'EM_ANDAMENTO')
                """, arquivoId);
    }

    /** Cria a próxima versão do conjunto na zona TRATADA (ainda sem arquivo no bucket, por isso sem chave_objeto). */
    public VersaoCriada criarVersao(long execucaoId) {
        Integer versao = jdbc.queryForObject(
                "SELECT NVL(MAX(versao), 0) + 1 FROM dataset_versao WHERE conjunto = ?",
                Integer.class, CONJUNTO);
        long id = inserirComId("""
                INSERT INTO dataset_versao (conjunto, versao, execucao_id, zona)
                VALUES (?, ?, ?, 'TRATADA')
                """, CONJUNTO, versao, execucaoId);
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
     * milhares de pontos). Para ver a geometria, use a linha no arquivo da zona bruta, que é imutável.
     */
    public void gravarQuarentena(long execucaoId, List<Resultado> reprovados) {
        if (reprovados.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(SQL_QUARENTENA, reprovados, reprovados.size(), (PreparedStatement ps, Resultado r) -> {
            ps.setLong(1, execucaoId);
            ps.setInt(2, r.imovel().linha());
            ps.setString(3, r.motivoQuarentena().startsWith("cod_imovel") ? "cod_imovel" : "geometria");
            ps.setString(4, r.motivoQuarentena());
            ps.setString(5, json(r));
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
        jdbc.update("""
                UPDATE arquivo_bruto SET status = 'PROCESSADO', processado_em = SYSTIMESTAMP
                 WHERE id = ?
                """, arquivoId);
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
