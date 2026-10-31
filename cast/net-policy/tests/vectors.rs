use playbridge_net_policy::NetworkPolicy;
use serde::Deserialize;
use std::net::IpAddr;

#[derive(Deserialize)]
struct Vector {
    name: String,
    url: String,
    allowed_private_origins: Vec<String>,
    dns_answers: Option<Vec<IpAddr>>,
    expected: String,
    note: String,
}

#[test]
fn shared_destination_vectors() {
    let vectors: Vec<Vector> = serde_json::from_str(include_str!("vectors.json")).unwrap();
    assert!(!vectors.is_empty());
    let mut names = std::collections::HashSet::new();
    for vector in vectors {
        assert!(names.insert(vector.name.clone()), "duplicate vector name");
        assert!(matches!(vector.expected.as_str(), "allow" | "deny"));
        let result = NetworkPolicy::new(vector.allowed_private_origins).and_then(|policy| {
            let url = policy.validate_url(&vector.url)?;
            // Missing DNS answers means use the URL's IP literal. No resolver or
            // live network is used by any of the three vector runners.
            let addresses = vector.dns_answers.unwrap_or_else(|| {
                url.host_str()
                    .and_then(|host| host.trim_matches(['[', ']']).parse().ok())
                    .into_iter()
                    .collect()
            });
            policy.validate_destination(&vector.url, &addresses)
        });
        assert_eq!(
            result.is_ok(),
            vector.expected == "allow",
            "{}: {} ({result:?})",
            vector.name,
            vector.note
        );
    }
}
