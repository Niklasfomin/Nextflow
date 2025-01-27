params.str = 'Hello world!'

process splitLetters {
    output:
    path 'chunk_*'

    """
    printf '${params.str}' | split -b 6 - chunk_
    """
}

process convertToUpper {
  input:
    path x
  output:
    stdout

  """
  rev $x
  """
}

workflow {
    splitLetters | flatten | convertToUpper | view { it.trim() }
}