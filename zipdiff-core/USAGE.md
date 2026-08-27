# Canonicalize a ZIP archive
java -jar zipdiff.jar canonicalize input.zip canonical.zip --level 9

# Generate a patch package between two archives
java -jar zipdiff.jar diff base.zip target.zip release.patch.zp \
--base-version 1.0.0 --target-version 1.1.0

# Apply a single patch
java -jar zipdiff.jar apply base.zip release.patch.zp reconstructed.zip

# Apply a chain of patches
java -jar zipdiff.jar apply-chain base.zip final.zip step1.patch.zp step2.patch.zp

# Extract and re-apply a signature block
java -jar zipdiff.jar sign-extract signed-canonical.zip mySchemeId --output-dir ./sig
java -jar zipdiff.jar sign-apply canonical.zip ./sig/mySchemeId.sig.json
