package geoRural.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ARQUIVO_BRUTO")
public class ArquivoBruto {

    public static final String STATUS_RECEBIDO = "RECEBIDO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "FONTE_ID", nullable = false)
    private Fonte fonte;

    @Column(name = "CHAVE_OBJETO", nullable = false, unique = true)
    private String chaveObjeto;

    @Column(name = "NOME_ORIGINAL", nullable = false)
    private String nomeOriginal;

    @Column(name = "TAMANHO_BYTES", nullable = false)
    private long tamanhoBytes;

    @Column(name = "CONTENT_TYPE")
    private String contentType;

    @Column(name = "HASH_SHA256", nullable = false)
    private String hashSha256;

    @Column(name = "STATUS", nullable = false)
    private String status = STATUS_RECEBIDO;

    @CreationTimestamp
    @Column(name = "RECEBIDO_EM", nullable = false, updatable = false)
    private OffsetDateTime recebidoEm;

    @Column(name = "PROCESSADO_EM")
    private OffsetDateTime processadoEm;

    // Getters e Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Fonte getFonte() { return fonte; }
    public void setFonte(Fonte fonte) { this.fonte = fonte; }

    public String getChaveObjeto() { return chaveObjeto; }
    public void setChaveObjeto(String chaveObjeto) { this.chaveObjeto = chaveObjeto; }

    public String getNomeOriginal() { return nomeOriginal; }
    public void setNomeOriginal(String nomeOriginal) { this.nomeOriginal = nomeOriginal; }

    public long getTamanhoBytes() { return tamanhoBytes; }
    public void setTamanhoBytes(long tamanhoBytes) { this.tamanhoBytes = tamanhoBytes; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getHashSha256() { return hashSha256; }
    public void setHashSha256(String hashSha256) { this.hashSha256 = hashSha256; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public OffsetDateTime getRecebidoEm() { return recebidoEm; }

    public OffsetDateTime getProcessadoEm() { return processadoEm; }
    public void setProcessadoEm(OffsetDateTime processadoEm) { this.processadoEm = processadoEm; }
}
