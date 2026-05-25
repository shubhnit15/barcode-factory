const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

module.exports = (req, res) => {
    // Only allow GET requests
    if (req.method !== 'GET') {
        res.setHeader('Content-Type', 'application/json');
        res.status(405).json({ error: 'Method Not Allowed. Use GET.' });
        return;
    }

    const { data, size = '200', fg = '000000', bg = 'FFFFFF' } = req.query;

    if (!data || data.trim() === '') {
        res.setHeader('Content-Type', 'application/json');
        res.status(400).json({ error: "Required query parameter 'data' is missing or empty." });
        return;
    }

    const sizeNum = parseInt(size, 10);
    if (isNaN(sizeNum) || sizeNum < 10 || sizeNum > 2000) {
        res.setHeader('Content-Type', 'application/json');
        res.status(400).json({ error: "Parameter 'size' must be a valid integer between 10 and 2000." });
        return;
    }

    // Determine platform-specific classpath separator
    const sep = process.platform === 'win32' ? ';' : ':';
    const cp = `lib/*${sep}java_engine`;

    const jreDir = path.resolve(__dirname, '..', 'jre');
    const processOptions = {
        cwd: path.resolve(__dirname, '..'),
        env: {
            ...process.env,
            JAVA_HOME: jreDir,
            LD_LIBRARY_PATH: `${path.join(jreDir, 'lib')}:${path.join(jreDir, 'lib', 'jli')}:${path.join(jreDir, 'lib', 'server')}:${process.env.LD_LIBRARY_PATH || ''}`
        }
    };

    // Check if bundled JRE exists
    let javaBin = 'java';
    const bundledJrePath = path.join(jreDir, 'bin', 'java');
    if (fs.existsSync(bundledJrePath)) {
        javaBin = bundledJrePath;
    }

    // Execute compiled Java program
    const javaProc = spawn(javaBin, ['-cp', cp, 'qrcode', data, size, fg, bg], processOptions);

    let stdoutBuffer = [];
    let stderrText = '';

    javaProc.stdout.on('data', (chunk) => {
        stdoutBuffer.push(chunk);
    });

    javaProc.stderr.on('data', (chunk) => {
        stderrText += chunk.toString();
    });

    javaProc.on('close', (code) => {
        if (code !== 0) {
            res.setHeader('Content-Type', 'application/json');
            res.status(500).json({ error: `Java execution failed: ${stderrText || 'Exit code ' + code}` });
            return;
        }

        const pngBytes = Buffer.concat(stdoutBuffer);
        res.setHeader('Content-Type', 'image/png');
        res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
        res.status(200).send(pngBytes);
    });
};
