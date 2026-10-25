"""Generate disposable synthetic test keys with OpenSSL, entirely offline."""
import argparse
import pathlib
import subprocess


def generate(folder, openssl):
    folder = pathlib.Path(folder).resolve()
    folder.mkdir(parents=True, exist_ok=True)

    def run(*args):
        subprocess.run([openssl, *args], cwd=folder, check=True, capture_output=True)

    root_ext = folder / "ca.cnf"
    root_ext.write_text("[req]\ndistinguished_name=dn\nx509_extensions=ca\nprompt=no\n[dn]\nCN=Duck renewal TEST root\n[ca]\nbasicConstraints=critical,CA:true,pathlen:3\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n")
    (folder / "leaf.cnf").write_text("basicConstraints=critical,CA:true,pathlen:0\nkeyUsage=critical,digitalSignature,keyCertSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n")
    (folder / "intermediate.cnf").write_text("basicConstraints=critical,CA:true,pathlen:1\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n")
    run("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", "root.key")
    run("req", "-new", "-x509", "-key", "root.key", "-days", "365", "-config", str(root_ext), "-out", "roots.pem")

    def certificate(name, days, issuer="roots.pem", issuer_key="root.key", extension="leaf.cnf"):
        run("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", name + ".key")
        run("req", "-new", "-key", name + ".key", "-subj", "/CN=Duck TEST " + name, "-out", name + ".csr")
        run("x509", "-req", "-in", name + ".csr", "-CA", issuer, "-CAkey", issuer_key, "-CAcreateserial", "-days", str(days), "-extfile", extension, "-out", name + ".pem")

    certificate("old", 3)
    certificate("new", 30)
    certificate("short-ca", 2, extension="intermediate.cnf")
    certificate("long-leaf", 30, "short-ca.pem", "short-ca.key")

    def box(name, certs, key=None):
        pem = (folder / (key or (name + ".key"))).read_text()
        chain = "".join("<Certificate format=\"pem\">" + (folder / cert).read_text() + "</Certificate>" for cert in certs)
        return '<AndroidAttestation><NumberOfKeyboxes>1</NumberOfKeyboxes><Keybox DeviceID="SYNTHETIC-TEST-ONLY"><Key algorithm="ecdsa"><PrivateKey format="pem">' + pem + '</PrivateKey><CertificateChain><NumberOfCertificates>' + str(len(certs)) + '</NumberOfCertificates>' + chain + '</CertificateChain></Key></Keybox></AndroidAttestation>'

    for name in ("old", "new"):
        (folder / (name + ".xml")).write_text(box(name, [name + ".pem", "roots.pem"]))
    (folder / "short.xml").write_text(box("long-leaf", ["long-leaf.pem", "short-ca.pem", "roots.pem"]))
    (folder / "mismatch.xml").write_text(box("new", ["new.pem", "roots.pem"], "old.key"))
    run("ec", "-in", "new.key", "-out", "sec1.key")
    (folder / "sec1.xml").write_text(box("new", ["new.pem", "roots.pem"], "sec1.key"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("folder")
    parser.add_argument("--openssl", default="openssl")
    args = parser.parse_args()
    generate(args.folder, args.openssl)
