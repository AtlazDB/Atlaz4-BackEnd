package geoRural.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "EXECUCAO")
public class Execucao {

    public static final String TIPO_PADRONIZACAO = "PADRONIZACAO";

    public static final String STATUS_EM_ANDAMENTO = "EM_ANDAMENTO";
    public static final String STATUS_CONCLUIDA    = "CONCLUIDA";
    public static final String STATUS_FALHOU       = "FALHOU";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ARQUIVO_BRUTO_ID", nullable = false)
    private ArquivoBruto arquivoBruto;

    @Column(name = "TIPO", nullable = false)
    private String tipo;

    @Column(name = "STATUS", nullable = false)
    private String status = STATUS_EM_ANDAMENTO;

    @Column(name = "REGISTROS_LIDOS")
    private Integer registrosLidos;

    @Column(name = "REGISTROS_VALIDOS")
    private Integer registrosValidos;

    @Column(name = "REGISTROS_INVALIDOS")
    private Integer registrosInvalidos;

    @CreationTimestamp
    @Column(name = "INICIADA_EM", nullable = false, updatable = false)
    private OffsetDateTime iniciadaEm;

    @Column(name = "FINALIZADA_EM")
    private OffsetDateTime finalizadaEm;

    @Column(name = "MENSAGEM_ERRO", length = 4000)
    private String mensagemErro;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ArquivoBruto getArquivoBruto() { return arquivoBruto; }
    public void setArquivoBruto(ArquivoBruto arquivoBruto) { this.arquivoBruto = arquivoBruto; }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getRegistrosLidos() { return registrosLidos; }
    public void setRegistrosLidos(Integer registrosLidos) { this.registrosLidos = registrosLidos; }

    public Integer getRegistrosValidos() { return registrosValidos; }
    public void setRegistrosValidos(Integer registrosValidos) { this.registrosValidos = registrosValidos; }

    public Integer getRegistrosInvalidos() { return registrosInvalidos; }
    public void setRegistrosInvalidos(Integer registrosInvalidos) { this.registrosInvalidos = registrosInvalidos; }

    public OffsetDateTime getIniciadaEm() { return iniciadaEm; }

    public OffsetDateTime getFinalizadaEm() { return finalizadaEm; }
    public void setFinalizadaEm(OffsetDateTime finalizadaEm) { this.finalizadaEm = finalizadaEm; }

    public String getMensagemErro() { return mensagemErro; }
    public void setMensagemErro(String mensagemErro) { this.mensagemErro = mensagemErro; }
}
