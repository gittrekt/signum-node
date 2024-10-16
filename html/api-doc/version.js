async function fetchVersion() {
  const headers = new Headers({
    'Accept': 'application/json'
  })
  const response = await fetch(`${location.origin}/api?requestType=getState`, {headers})
  if (response.ok) {
    const {version} = await response.json()
    return version;
  }
  return ""
}
