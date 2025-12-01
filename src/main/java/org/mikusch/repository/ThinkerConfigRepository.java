package org.mikusch.repository;

import org.mikusch.entity.ThinkerConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ThinkerConfigRepository extends JpaRepository<ThinkerConfig, Long> {

}
