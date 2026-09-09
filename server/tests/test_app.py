from fastapi.testclient import TestClient

from app import app


def test_health_endpoint_reports_v03():
    client = TestClient(app)
    response = client.get('/health')
    assert response.status_code == 200
    payload = response.json()
    assert payload['ok'] is True
    assert payload['version'] == '0.3.0'
    assert 'live' in payload['modes']
    assert 'multivoice' in payload['modes']
