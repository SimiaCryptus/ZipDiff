

```shell
ZIPDIFF=/home/andrew/code/Zip-Diff/zipdiff/zipdiff-core/build/libs/zipdiff-cli-1.0.0-SNAPSHOT-all.jar

# Canonicalize a ZIP archive
java -jar $ZIPDIFF canonicalize input.zip canonical.zip --level 9

# Generate a patch package between two archives
java -jar $ZIPDIFF diff base.zip target.zip release.patch.zp --base-version 1.0.0 --target-version 1.1.0

# Apply a single patch
java -jar $ZIPDIFF apply base.zip release.patch.zp reconstructed.zip

# Apply a chain of patches
java -jar $ZIPDIFF apply-chain base.zip final.zip step1.patch.zp step2.patch.zp

# Extract and re-apply a signature block
java -jar $ZIPDIFF sign-extract signed-canonical.zip mySchemeId --output-dir ./sig
java -jar $ZIPDIFF sign-apply canonical.zip ./sig/mySchemeId.sig.json
```


```shell
# Produce a zip archive of the working tree at a given git commit.
# Useful for generating test fixtures (e.g. base.zip/target.zip) directly
# from repository history without manually checking out commits.
#
# Usage: git_commit_to_zip <commit-ish> <output.zip> [path...]
git_commit_to_zip() {
   local commit="$1"
   local output="$2"
   shift 2
   if [ -z "$commit" ] || [ -z "$output" ]; then
     echo "Usage: git_commit_to_zip <commit-ish> <output.zip> [path...]" >&2
     return 1
   fi
   git archive --format=zip -o "$output" "$commit" "$@"
}
# Example: build a zip from commit abc1234, and another from HEAD,
# then diff them with zipdiff.
git_commit_to_zip abc1234 base.zip
git_commit_to_zip HEAD target.zip
java -jar $ZIPDIFF diff base.zip target.zip release.patch.zp --base-version 1.0.0 --target-version 1.1.0
```


```shell
git_commit_to_zip HEAD head0.zip
git_commit_to_zip HEAD^ head1.zip
ZIPDIFF=/home/andrew/code/Zip-Diff/zipdiff/zipdiff-core/build/libs/zipdiff-cli-1.0.0-SNAPSHOT-all.jar
java -jar $ZIPDIFF diff head0.zip head1.zip patch.zp --base-version 1.0.0 --target-version 1.1.0
```