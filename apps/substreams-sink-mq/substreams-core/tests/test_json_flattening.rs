// Test JSON flattening functionality
use std::collections::HashMap;
use substreams_core::protobuf::{FieldValue, ParsedMessage};
use substreams_core::routing::dynamic::{GenericMessage, LazyJsonFields};

#[test]
fn test_json_flattening_basic() {
    // Create test data with nested structure
    let mut fields = HashMap::new();

    // Simple fields
    fields.insert(
        "tx_hash".to_string(),
        FieldValue::String("abc123".to_string()),
    );
    fields.insert("event_index".to_string(), FieldValue::Uint32(42));

    // Nested balance_update structure
    let mut balance_update_fields = HashMap::new();
    balance_update_fields.insert(
        "bold_balance".to_string(),
        FieldValue::String("1000000".to_string()),
    );
    balance_update_fields.insert(
        "coll_balance".to_string(),
        FieldValue::String("2000000".to_string()),
    );

    let balance_update_message = ParsedMessage {
        type_url: "balance_update".to_string(),
        fields: balance_update_fields,
    };
    fields.insert(
        "balance_update".to_string(),
        FieldValue::Message(Box::new(balance_update_message)),
    );

    // Create GenericMessage
    let generic_message = GenericMessage {
        message_type: "active_pool_update".to_string(),
        block_number: 12345,
        processed_at: "2025-09-24T00:30:00Z".to_string(),
        fields: LazyJsonFields::Raw(fields),
    };

    // Serialize to JSON
    let json_string = serde_json::to_string_pretty(&generic_message).expect("Serialization failed");
    println!("Flattened JSON output:\n{}", json_string);

    // Parse back to verify structure
    let json_value: serde_json::Value = serde_json::from_str(&json_string).expect("Parse failed");
    let obj = json_value.as_object().expect("Should be object");

    // Verify metadata fields exist
    assert_eq!(
        obj.get("message_type").unwrap().as_str().unwrap(),
        "active_pool_update"
    );
    assert_eq!(obj.get("block_number").unwrap().as_u64().unwrap(), 12345);
    assert!(obj.get("processed_at").is_some());

    // Verify simple fields are flattened
    assert_eq!(obj.get("tx_hash").unwrap().as_str().unwrap(), "abc123");
    assert_eq!(obj.get("event_index").unwrap().as_u64().unwrap(), 42);

    // Verify nested fields are flattened with underscore separator
    assert_eq!(
        obj.get("balance_update_bold_balance")
            .unwrap()
            .as_str()
            .unwrap(),
        "1000000"
    );
    assert_eq!(
        obj.get("balance_update_coll_balance")
            .unwrap()
            .as_str()
            .unwrap(),
        "2000000"
    );

    // Verify no nested 'fields' object exists
    assert!(
        obj.get("fields").is_none(),
        "Should not have nested 'fields' object"
    );

    println!("✅ All flattening assertions passed!");
}
