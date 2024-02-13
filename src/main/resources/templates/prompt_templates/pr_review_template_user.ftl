PR Info:

Title: '${title}'

Branch: '${branch}'

<#if description??>

Description:
'
${description}
'
</#if>

<#if language??>

Main PR language: '${language}'
</#if>
<#if commitMessages??>

Commit messages:
'
${commitMessages}
'
</#if>

<#if questions??>
######
Here are questions to better understand the PR. Use the answers to provide better feedback.

${questions}

User answers:
'
${answers}
'
######
</#if>

The PR Git Diff:
```
${diff}
```


Response (should be a valid YAML, and nothing else):
```yaml