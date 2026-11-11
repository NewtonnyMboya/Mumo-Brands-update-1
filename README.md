# Mumo Brands Printing and Branding Services

Native Android application project. Package: `com.mumobrands.printing`.

## Implemented modules
- Kotlin + Jetpack Compose Material 3; navy, magenta and white company theme.
- Company logo and custom MB launcher icon.
- Collapsible navigation drawer; company contact footer.
- Private on-device SQLite database with schema migration to version 2.
- Customer order creation, editing and deletion.
- Printable order/payment receipt through Android system print service.
- Clothing and merchandise stock creation, editing and deletion.
- Audited stock IN, OUT and ADJUST movements with negative-stock protection.
- Stock movement history.
- First-run staff PIN setup and sign-in; PIN protected with PBKDF2-HMAC-SHA256 and random salt; change PIN in Settings.
- Full SQLite database backup to a user-selected location (Storage Access Framework).
- CSV export of orders, stock and stock-movement history.
- GitHub Actions debug APK build and optional company-key signed release APK.

## Build locally
Requires JDK 17, Android SDK Platform 35 and Gradle 8.9 or compatible. From this directory:

```bash
gradle assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Production APK signing
A release APK is signed only when the company keystore and all signing values are supplied through environment variables. Never commit the keystore, passwords, or private key to Git.

Create or obtain the company's long-term Android release keystore and preserve it securely. Do not casually replace it: future updates must be signed with the same key to update an existing installation.

Add these GitHub Actions repository secrets:

- `MUMO_KEYSTORE_BASE64`: Base64-encoded contents of the company `.jks` / `.keystore` file.
- `MUMO_KEYSTORE_PASSWORD`: keystore password.
- `MUMO_KEY_ALIAS`: key alias.
- `MUMO_KEY_PASSWORD`: private-key password.

Example to encode a keystore locally (do not paste the output into chat or commit it):

```bash
base64 -w 0 mumo-release.jks
```

When all four secrets exist, GitHub Actions builds and uploads `Mumo-Brands-production-signed-apk`. Without them, only the installable debug APK is produced; it is not a production release.

## Data and security notes
- The staff PIN is a local app gate, not a cloud identity system or multi-user permission model.
- Database backup and CSV exports can contain personal/customer and financial information. Keep them private.
- The SQLite backup is an export; a guided restore/import screen is not yet implemented.
- This source has not yet been compiled or device-tested in this environment. Run the GitHub Actions build and test on a physical Android device before business deployment.
