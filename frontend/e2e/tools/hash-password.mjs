import bcrypt from 'bcryptjs'

let password = ''
if (process.env.TEST_ACCOUNT_PASSWORD_BASE64) {
  password = Buffer.from(process.env.TEST_ACCOUNT_PASSWORD_BASE64, 'base64').toString('utf8')
} else {
  const chunks = []
  for await (const chunk of process.stdin) chunks.push(chunk)
  password = Buffer.concat(chunks).toString('utf8')
}

if (password.length < 8 || password.length > 128) {
  process.stderr.write('Password length must be between 8 and 128 characters.\n')
  process.exit(2)
}

process.stdout.write(await bcrypt.hash(password, 10))
