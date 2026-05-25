const fs = require('fs');
const path = require('path');
const https = require('https');
const { execSync } = require('child_process');

const libDir = path.join(__dirname, 'lib');
if (!fs.existsSync(libDir)) {
    fs.mkdirSync(libDir);
}

const urls = {
    'core-3.5.3.jar': 'https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar',
    'javase-3.5.3.jar': 'https://repo1.maven.org/maven2/com/google/zxing/javase/3.5.3/javase-3.5.3.jar',
    'jcommander-1.82.jar': 'https://repo1.maven.org/maven2/com/beust/jcommander/1.82/jcommander-1.82.jar'
};

function downloadFile(url, dest) {
    return new Promise((resolve, reject) => {
        function get(currentUrl) {
            https.get(currentUrl, (response) => {
                if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
                    get(response.headers.location);
                    return;
                }
                if (response.statusCode !== 200) {
                    reject(new Error(`Failed to download ${currentUrl}: ${response.statusCode}`));
                    return;
                }
                const file = fs.createWriteStream(dest);
                response.pipe(file);
                file.on('finish', () => {
                    file.close(resolve);
                });
            }).on('error', (err) => {
                fs.unlink(dest, () => {});
                reject(err);
            });
        }
        get(url);
    });
}

async function main() {
    try {
        console.log('--- Custom Java Build Phase Started ---');
        
        // 1. Download ZXing dependencies
        console.log('Downloading ZXing dependencies...');
        for (const [name, url] of Object.entries(urls)) {
            const dest = path.join(libDir, name);
            if (!fs.existsSync(dest)) {
                console.log(`Downloading ${name} from Maven Central...`);
                await downloadFile(url, dest);
            } else {
                console.log(`${name} already exists.`);
            }
        }
        console.log('Dependencies downloaded successfully.');

        // 2. Download and extract Linux JRE if running on Linux (e.g., Vercel environment)
        const jreDir = path.join(__dirname, 'jre');
        if (process.platform === 'linux') {
            if (!fs.existsSync(path.join(jreDir, 'bin', 'java'))) {
                console.log('Detected Linux platform. Downloading bundled JRE 17 from Adoptium...');
                const jreTarPath = path.join(__dirname, 'jre.tar.gz');
                const jreUrl = 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jre/hotspot/normal/eclipse';
                
                await downloadFile(jreUrl, jreTarPath);
                console.log('Bundled JRE downloaded. Extracting...');
                
                // Clean up any old jre folder
                if (fs.existsSync(jreDir)) {
                    fs.rmdirSync(jreDir, { recursive: true });
                }
                
                // Extract using system tar
                execSync(`tar -xzf "${jreTarPath}"`, { stdio: 'inherit' });
                
                // Find the extracted folder (it starts with jdk- or jre-)
                const files = fs.readdirSync(__dirname);
                const extractedFolder = files.find(f => (f.startsWith('jdk-') || f.startsWith('jre-')) && fs.statSync(path.join(__dirname, f)).isDirectory());
                
                if (extractedFolder) {
                    fs.renameSync(path.join(__dirname, extractedFolder), jreDir);
                    console.log(`Successfully renamed ${extractedFolder} to jre.`);
                    
                    // Delete man pages (which contain dynamic symlinks that break Vercel's packaging engine)
                    const manDir = path.join(jreDir, 'man');
                    if (fs.existsSync(manDir)) {
                        fs.rmdirSync(manDir, { recursive: true });
                        console.log('Deleted jre/man to prevent symlink deployment errors.');
                    }
                    
                    // Delete legal documents to further reduce package size
                    const legalDir = path.join(jreDir, 'legal');
                    if (fs.existsSync(legalDir)) {
                        fs.rmdirSync(legalDir, { recursive: true });
                        console.log('Deleted jre/legal to reduce deployment package footprint.');
                    }
                } else {
                    throw new Error('Failed to locate extracted JRE directory.');
                }
                
                // Delete tarball
                fs.unlinkSync(jreTarPath);
                console.log('Bundled JRE extraction complete and cleaned up tarball.');
            } else {
                console.log('Bundled Linux JRE already exists.');
            }
        } else {
            console.log(`Skipping Linux JRE download on non-Linux platform: ${process.platform}`);
        }

        // 3. Compile Java classes (if javac compiler is available)
        console.log('Compiling Java classes (if javac compiler is available)...');
        try {
            execSync(`javac -d java_engine -cp "lib/*" java_engine/qrcode.java java_engine/barcode.java`, { stdio: 'inherit' });
            console.log('Java compilation complete.');
        } catch (e) {
            console.log('--- INFO: javac compiler not found or failed. Using pre-compiled .class bytecodes ---');
        }
        
        console.log('--- Custom Java Build Phase Succeeded ---');
    } catch (err) {
        console.error('Build phase failed:', err);
        process.exit(1);
    }
}

main();
